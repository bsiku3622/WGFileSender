package com.jaewonbaek.wgfilesender.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
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
import com.jaewonbaek.wgfilesender.net.ListenerService
import com.jaewonbaek.wgfilesender.net.SendClient
import com.jaewonbaek.wgfilesender.net.UpdateService
import com.jaewonbaek.wgfilesender.net.UpdateState
import com.jaewonbaek.wgfilesender.ui.Lang
import com.jaewonbaek.wgfilesender.ui.S
import com.jaewonbaek.wgfilesender.ui.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

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
    val transfers = MutableStateFlow(store.loadTransfers())
    /** Smoothed transfer rate (bytes/sec) per active transfer id, for the speed/ETA readout. */
    val transferRates = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val rateSamples = mutableMapOf<String, Pair<Long, Long>>()   // bytes to epochMillis
    val selectedTab = MutableStateFlow(0)   // 0 Devices · 1 Transfers · 2 Settings
    val pendingPairing = MutableStateFlow<PendingPairing?>(null)
    val outgoingPairing = MutableStateFlow<OutgoingPairing?>(null)
    val listenerRunning = MutableStateFlow(false)
    val listenerError = MutableStateFlow<String?>(null)
    val sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val language = MutableStateFlow(Lang.from(store.loadLanguage()))
    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    private val config = SharedConfig(identity.value, peers.value, settings.value)
    private val sendClient = SendClient(context, config)
    private val listener = HttpListener(context, config, this)
    private val updateService = UpdateService(context)
    private var pairDeferred: CompletableDeferred<String?>? = null

    val appVersion: String get() = updateService.currentVersion

    init {
        config.language = language.value
        checkForUpdates(manual = false)   // quiet check once per process start
    }

    fun setLanguage(lang: Lang) {
        language.value = lang
        store.saveLanguage(lang.name)
        config.language = lang
    }

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
        // One prompt at a time; reject overlaps instead of clobbering the pending request.
        if (pendingPairing.value != null) return null
        val deferred = CompletableDeferred<String?>()
        pairDeferred = deferred
        pendingPairing.value = PendingPairing(body, address)
        // PROTOCOL.md: decline/timeout after 60s.
        val token = withTimeoutOrNull(60_000) { deferred.await() }
        if (token == null && pairDeferred === deferred) {   // timed out — clean up the prompt
            pairDeferred = null
            pendingPairing.value = null
        }
        return token
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
    override fun onTransferFinish(id: String, state: TransferState, error: String?, savedPath: String?) =
        finishTransfer(id, state, error, savedPath)

    // MARK: outgoing pairing

    fun startOutgoingPair(address: String) {
        val pin = randomPin()
        outgoingPairing.value = OutgoingPairing(
            address, pin, String.format(str(S.confirmPinOnOther, language.value), pretty(pin)))
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
                    ?.copy(status = String.format(str(S.pairingFailed, language.value), e.message), failed = true)
            }
        }
    }

    fun dismissOutgoingPair() { outgoingPairing.value = null }

    // MARK: updates

    fun checkForUpdates(manual: Boolean) {
        when (updateState.value) {
            is UpdateState.Checking, is UpdateState.Downloading -> return
            else -> {}
        }
        updateState.value = UpdateState.Checking
        scope.launch {
            try {
                val info = updateService.checkForUpdate()
                updateState.value = when {
                    info != null -> UpdateState.Available(info)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                updateState.value = if (manual) UpdateState.Failed(e.message ?: "error") else UpdateState.Idle
            }
        }
    }

    fun downloadUpdate() {
        val st = updateState.value
        if (st !is UpdateState.Available) return
        val info = st.info
        if (info.assetUrl == null) { openUrl(info.pageUrl); return }   // no APK asset → open page
        updateState.value = UpdateState.Downloading(0f)
        scope.launch {
            try {
                val file = updateService.download(info) { p ->
                    if (updateState.value is UpdateState.Downloading) updateState.value = UpdateState.Downloading(p)
                }
                updateState.value = UpdateState.Downloaded(file)
            } catch (e: Exception) {
                updateState.value = UpdateState.Failed(e.message ?: "error")
            }
        }
    }

    fun installUpdate() {
        val st = updateState.value
        if (st !is UpdateState.Downloaded) return
        // Android 8+ gates sideload installs behind a per-app "install unknown apps" permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", st.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun dismissUpdate() { updateState.value = UpdateState.Idle }

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // MARK: sending

    fun setTab(index: Int) { selectedTab.value = index }

    // Cap concurrent uploads so sending many files doesn't swamp the receiver.
    private val sendSemaphore = Semaphore(4)
    private val activeSendJobs = mutableMapOf<String, Job>()

    fun sendFiles(uris: List<Uri>, peer: PeerDevice) {
        if (uris.isNotEmpty()) selectedTab.value = 1   // jump to Transfers
        for (uri in uris) {
            val meta = UriUtil.metadata(context, uri)
            val transferId = UUID.randomUUID().toString()
            upsertTransfer(Transfer(transferId, TransferDirection.OUTGOING, peer.displayName,
                meta.name, meta.size, state = TransferState.QUEUED,
                localPath = uri.toString(), peerId = peer.peerId))
            launchSend(transferId, peer, uri, meta.name)
        }
        sharedUris.value = emptyList()
    }

    private fun launchSend(
        transferId: String, peer: PeerDevice, uri: Uri, name: String,
        resumeFirst: Boolean = false
    ) {
        val job = scope.launch {
            try {
                sendSemaphore.withPermit {
                    markActive(transferId)   // queued → active once a slot frees up
                    var attempt = 0
                    var tryResume = resumeFirst
                    while (true) {
                        try {
                            sendClient.sendFile(peer, uri, name, transferId, tryResume,
                                onSize = { updateTotal(transferId, it) }) { sent ->
                                updateProgress(transferId, sent)
                            }
                            finishTransfer(transferId, TransferState.COMPLETED, null)
                            break
                        } catch (e: CancellationException) {
                            // Cancellation is recorded as resumable, then propagated.
                            finishTransfer(transferId, TransferState.INTERRUPTED, str(S.canceled, language.value))
                            throw e
                        } catch (e: Exception) {
                            attempt++
                            if (attempt > MAX_SEND_RETRIES) {
                                finishTransfer(transferId, TransferState.INTERRUPTED, e.message)
                                break
                            }
                            setRetrying(transferId)
                            try {
                                delay(1500)   // brief backoff, then resume from the receiver's last byte
                            } catch (e: CancellationException) {
                                finishTransfer(transferId, TransferState.INTERRUPTED, str(S.canceled, language.value))
                                throw e
                            }
                            tryResume = true
                        }
                    }
                }
            } finally {
                activeSendJobs.remove(transferId)
            }
        }
        activeSendJobs[transferId] = job
    }

    /** Keeps the row active but flags that we're reconnecting between auto-retry attempts. */
    private fun setRetrying(id: String) {
        transfers.value = transfers.value.map {
            if (it.id == id) it.copy(error = str(S.retrying, language.value)) else it
        }
        clearRate(id)   // rate restarts when bytes flow again
    }

    /** Cancels an in-flight transfer; partial bytes are kept on the receiver for resume. */
    fun cancelTransfer(transfer: Transfer) {
        activeSendJobs[transfer.id]?.cancel()
    }

    /** Resumes an interrupted/failed transfer from where the receiver left off. */
    fun resumeTransfer(transfer: Transfer) {
        if (transfer.direction != TransferDirection.OUTGOING) return
        val uri = transfer.localPath?.let { Uri.parse(it) } ?: return
        val peer = peers.value.firstOrNull { it.peerId == transfer.peerId } ?: return
        val meta = UriUtil.metadata(context, uri)
        transfers.value = transfers.value.map {
            if (it.id == transfer.id) it.copy(state = TransferState.ACTIVE, error = null) else it
        }
        launchSend(transfer.id, peer, uri, meta.name, resumeFirst = true)
    }

    /** queued → active once a concurrency slot opens. */
    private fun markActive(id: String) {
        transfers.value = transfers.value.map {
            if (it.id == id && it.state == TransferState.QUEUED) it.copy(state = TransferState.ACTIVE) else it
        }
    }

    /** Corrects totalBytes once the sender has measured the real stream length. */
    private fun updateTotal(id: String, total: Long) {
        transfers.value = transfers.value.map {
            if (it.id == id) it.copy(totalBytes = total) else it
        }
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

    /** Toggles the background-receive foreground service. Off → service stops and the
     *  notification disappears (no files received while off). */
    fun setBackgroundReceive(enabled: Boolean) {
        settings.value = settings.value.copy(backgroundReceive = enabled)
        persistSettings()
        val intent = Intent(context, ListenerService::class.java)
        if (enabled) androidx.core.content.ContextCompat.startForegroundService(context, intent)
        else context.stopService(intent)
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
        // Instantaneous rate, smoothed with an EMA and sampled at most ~3x/sec.
        val t = now()
        val prev = rateSamples[id]
        if (prev == null) {
            rateSamples[id] = bytes to t
        } else {
            val dt = (t - prev.second) / 1000.0
            if (dt >= 0.3) {
                val inst = (bytes - prev.first) / dt
                val smoothed = transferRates.value[id]?.let { it * 0.6 + inst * 0.4 } ?: inst
                transferRates.value = transferRates.value + (id to maxOf(0.0, smoothed))
                rateSamples[id] = bytes to t
            }
        }
    }

    private fun clearRate(id: String) {
        if (id in transferRates.value) transferRates.value = transferRates.value - id
        rateSamples.remove(id)
    }

    private fun finishTransfer(id: String, state: TransferState, error: String?, savedPath: String? = null) {
        transfers.value = transfers.value.map {
            if (it.id != id) it
            else it.copy(state = state, error = error,
                localPath = savedPath ?: it.localPath,
                transferredBytes = if (state == TransferState.COMPLETED) it.totalBytes else it.transferredBytes)
        }
        clearRate(id)
        persistTransfers()
    }

    // MARK: transfer file actions

    fun transferFileExists(transfer: Transfer): Boolean {
        val uri = transfer.localPath?.let { Uri.parse(it) } ?: return false
        return runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
    }

    fun openTransfer(transfer: Transfer) {
        val uri = transfer.localPath?.let { Uri.parse(it) } ?: return
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun renameTransfer(transfer: Transfer, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val uri = transfer.localPath?.let { Uri.parse(it) } ?: return
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return
        if (runCatching { doc.renameTo(trimmed) }.getOrDefault(false)) {
            val newUri = doc.uri.toString()
            transfers.value = transfers.value.map {
                if (it.id == transfer.id) it.copy(fileName = trimmed, localPath = newUri) else it
            }
            persistTransfers()
        }
    }

    fun deleteTransfer(transfer: Transfer) {
        transfer.localPath?.let { uri ->
            runCatching { DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() }
        }
        transfers.value = transfers.value.filterNot { it.id == transfer.id }
        persistTransfers()
    }

    /** Removes a row without touching any file (sent transfers keep their source). */
    fun removeTransfer(transfer: Transfer) {
        transfers.value = transfers.value.filterNot { it.id == transfer.id }
        persistTransfers()
    }

    fun openDownloadFolder() {
        val treeUri = settings.value.downloadTreeUri?.let { Uri.parse(it) } ?: return
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull() ?: treeUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun clearFinished() {
        // Keep active and interrupted (resumable) rows; clear only completed/failed.
        transfers.value = transfers.value.filterNot {
            it.state == TransferState.COMPLETED || it.state == TransferState.FAILED
        }
        persistTransfers()
    }

    /** Persist finished transfers only; in-flight ones are gone after a restart anyway. */
    private fun persistTransfers() {
        store.saveTransfers(transfers.value.filterNot {
            it.state == TransferState.ACTIVE || it.state == TransferState.QUEUED
        })
    }

    val downloadFolderSet: Boolean get() = settings.value.downloadTreeUri != null

    companion object {
        /** Auto-retry attempts (each resumes from the receiver's last byte) before parking
         *  a transfer as INTERRUPTED for a manual resume. */
        private const val MAX_SEND_RETRIES = 2
        // Bearer tokens and the pairing PIN are credentials — use a CSPRNG, not kotlin.random.
        private val secureRandom = java.security.SecureRandom()
        private fun now() = System.currentTimeMillis()
        fun randomPin() = "%06d".format(secureRandom.nextInt(1_000_000))
        fun pretty(pin: String) = if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin
        fun randomToken(): String {
            val b = ByteArray(24); secureRandom.nextBytes(b)
            return Base64.encodeToString(b, Base64.NO_WRAP)
        }
    }
}
