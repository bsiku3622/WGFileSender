package com.jaewonbaek.wgfilesender.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.jaewonbaek.wgfilesender.model.Identity
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Settings
import com.jaewonbaek.wgfilesender.model.Transfer
import com.jaewonbaek.wgfilesender.model.TransferDirection
import com.jaewonbaek.wgfilesender.model.TransferState
import com.jaewonbaek.wgfilesender.net.HttpListener
import com.jaewonbaek.wgfilesender.net.ListenerEvents
import com.jaewonbaek.wgfilesender.net.SendClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Singleton coordinator: owns persistence, the shared config, the send client and the
 * listener, exposes state as flows, and implements the listener callbacks. Lives in the
 * Application so the foreground service and the UI share one instance.
 */
class AppController(private val context: Context) : ListenerEvents {
    private val store = Store(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val identity = MutableStateFlow(store.loadIdentity())
    val peers = MutableStateFlow(store.loadPeers())
    val settings = MutableStateFlow(store.loadSettings())
    val transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val pendingPairing = MutableStateFlow<PendingPairing?>(null)
    val outgoingPairing = MutableStateFlow<OutgoingPairing?>(null)
    val listenerRunning = MutableStateFlow(false)
    val listenerError = MutableStateFlow<String?>(null)
    val sharedUris = MutableStateFlow<List<Uri>>(emptyList())

    private val config = SharedConfig(identity.value, peers.value, settings.value)
    private val sendClient = SendClient(context, config)
    private val listener = HttpListener(context, config, this)
    private var pairDeferred: CompletableDeferred<String?>? = null

    data class PendingPairing(val body: PairRequestBody, val address: String)
    data class OutgoingPairing(
        val address: String, val pin: String, val status: String, val failed: Boolean = false
    )

    // MARK: listener lifecycle (driven by ListenerService)

    fun startListener() {
        if (listenerRunning.value) return
        runCatching { listener.start(settings.value.port) }
            .onSuccess { listenerRunning.value = true; listenerError.value = null }
            .onFailure { listenerError.value = "Couldn't bind port ${settings.value.port}: ${it.message}" }
    }

    fun stopListener() {
        listener.stop()
        listenerRunning.value = false
    }

    // MARK: incoming pairing (ListenerEvents)

    override suspend fun onPairRequest(body: PairRequestBody, address: String): String? {
        val deferred = CompletableDeferred<String?>()
        pairDeferred = deferred
        pendingPairing.value = PendingPairing(body, address)
        return deferred.await()
    }

    fun acceptIncomingPair() {
        val pending = pendingPairing.value ?: return
        val token = randomToken()   // tokenIn we issue; the peer sends it back to us
        upsertPeer(PeerDevice(pending.body.deviceId, pending.body.deviceName, null,
            pending.address, tokenOut = "", tokenIn = token, lastSeen = now()))
        pendingPairing.value = null
        pairDeferred?.complete(token); pairDeferred = null
    }

    fun declineIncomingPair() {
        pendingPairing.value = null
        pairDeferred?.complete(null); pairDeferred = null
    }

    override fun onPairConfirm(body: PairConfirmBody) {
        val peer = peers.value.firstOrNull { it.peerId == body.deviceId } ?: return
        upsertPeer(peer.copy(tokenOut = body.token))
    }

    override fun onTransferStart(transfer: Transfer) = upsertTransfer(transfer)
    override fun onTransferProgress(id: String, bytes: Long) = updateProgress(id, bytes)
    override fun onTransferFinish(id: String, state: TransferState, error: String?) =
        finishTransfer(id, state, error)

    // MARK: outgoing pairing

    fun startOutgoingPair(address: String) {
        val pin = randomPin()
        outgoingPairing.value = OutgoingPairing(
            address, pin, "Confirm PIN ${pretty(pin)} on the other device, then accept there.")
        scope.launch {
            try {
                val resp = sendClient.requestPair(address, UUID.randomUUID().toString(), pin)
                val ourToken = randomToken()   // tokenIn we issue for the peer
                upsertPeer(PeerDevice(resp.deviceId, resp.deviceName, null, address,
                    tokenOut = resp.token, tokenIn = ourToken, lastSeen = now()))
                sendClient.confirmPair(address, resp.token, ourToken)
                outgoingPairing.value = null
            } catch (e: Exception) {
                outgoingPairing.value = outgoingPairing.value
                    ?.copy(status = "Pairing failed: ${e.message}", failed = true)
            }
        }
    }

    fun dismissOutgoingPair() { outgoingPairing.value = null }

    // MARK: sending

    fun sendFiles(uris: List<Uri>, peer: PeerDevice) {
        for (uri in uris) {
            val meta = UriUtil.metadata(context, uri)
            val transferId = UUID.randomUUID().toString()
            upsertTransfer(Transfer(transferId, TransferDirection.OUTGOING, peer.displayName,
                meta.name, meta.size))
            scope.launch {
                try {
                    sendClient.sendFile(peer, uri, meta.name, meta.size, transferId) { sent ->
                        updateProgress(transferId, sent)
                    }
                    finishTransfer(transferId, TransferState.COMPLETED, null)
                } catch (e: Exception) {
                    finishTransfer(transferId, TransferState.FAILED, e.message)
                }
            }
        }
        sharedUris.value = emptyList()
    }

    fun setSharedUris(uris: List<Uri>) { sharedUris.value = uris }

    // MARK: peers

    fun renamePeer(peer: PeerDevice, name: String) = upsertPeer(peer.copy(localName = name.trim()))

    fun removePeer(peer: PeerDevice) {
        peers.value = peers.value.filterNot { it.peerId == peer.peerId }
        persistPeers()
    }

    private fun upsertPeer(peer: PeerDevice) {
        val list = peers.value.toMutableList()
        val i = list.indexOfFirst { it.peerId == peer.peerId }
        if (i >= 0) list[i] = peer else list.add(peer)
        peers.value = list
        persistPeers()
    }

    private fun persistPeers() {
        store.savePeers(peers.value)
        config.peers = peers.value
    }

    // MARK: settings

    fun setDownloadTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.value = settings.value.copy(downloadTreeUri = uri.toString())
        persistSettings()
    }

    fun setDeviceName(name: String) {
        identity.value = identity.value.copy(deviceName = name.trim())
        store.saveIdentity(identity.value)
        config.identity = identity.value
    }

    fun setPort(port: Int) {
        settings.value = settings.value.copy(port = port)
        persistSettings()
        if (listenerRunning.value) {
            runCatching { listener.restart(port) }
                .onFailure { listenerError.value = "Couldn't bind port $port: ${it.message}" }
        }
    }

    private fun persistSettings() {
        store.saveSettings(settings.value)
        config.settings = settings.value
    }

    // MARK: transfers

    private fun upsertTransfer(t: Transfer) {
        val list = transfers.value.toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list.add(0, t)
        transfers.value = list
    }

    private fun updateProgress(id: String, bytes: Long) {
        transfers.value = transfers.value.map { if (it.id == id) it.copy(transferredBytes = bytes) else it }
    }

    private fun finishTransfer(id: String, state: TransferState, error: String?) {
        transfers.value = transfers.value.map {
            if (it.id != id) it
            else it.copy(state = state, error = error,
                transferredBytes = if (state == TransferState.COMPLETED) it.totalBytes else it.transferredBytes)
        }
    }

    fun clearFinished() {
        transfers.value = transfers.value.filter { it.state == TransferState.ACTIVE }
    }

    val downloadFolderSet: Boolean get() = settings.value.downloadTreeUri != null

    companion object {
        private fun now() = System.currentTimeMillis()
        fun randomPin() = "%06d".format(Random.nextInt(0, 1_000_000))
        fun pretty(pin: String) = if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin
        fun randomToken(): String {
            val b = ByteArray(24); Random.nextBytes(b)
            return Base64.encodeToString(b, Base64.NO_WRAP)
        }
    }
}
