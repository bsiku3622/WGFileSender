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
import com.jaewonbaek.wgfilesender.model.OfferBody
import com.jaewonbaek.wgfilesender.model.OfferFile
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
import kotlinx.coroutines.flow.update
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
    // Touched from multiple IO coroutines + Ktor threads, so use a concurrent map.
    private val rateSamples = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()   // bytes to epochMillis
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
        restoreTransfers()   // re-arm outgoing sources + resume incoming pulls
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

    override fun onOffer(offer: OfferBody, peer: PeerDevice, senderName: String) =
        handleOffer(offer, peer, senderName)
    override fun onPullProgress(fileId: String, sent: Long, total: Long) =
        updateOutgoingProgress(fileId, sent, total)

    // MARK: outgoing pairing

    fun startOutgoingPair(address: String) {
        val pin = randomPin()
        outgoingPairing.value = OutgoingPairing(
            address, pin, String.format(str(S.confirmPinOnOther, language.value), pretty(pin)))
        scope.launch {
            try {
                val resp = sendClient.requestPair(address, UUID.randomUUID().toString(), pin)
                val ourToken = randomToken()   // tokenIn we issue for the peer
                // Confirm first; persist the peer only once the symmetric exchange succeeds.
                sendClient.confirmPair(address, resp.token, ourToken)
                upsertPeer(PeerDevice(resp.deviceId, resp.deviceName, null, address,
                    tokenOut = resp.token, tokenIn = ourToken, lastSeen = now()))
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

    // MARK: sending (announce a manifest, then serve /pull)

    fun setTab(index: Int) { selectedTab.value = index }

    fun sendFiles(uris: List<Uri>, peer: PeerDevice) {
        if (uris.isEmpty()) return
        selectedTab.value = 1
        val batchId = UUID.randomUUID().toString()
        val entries = uris.map { uri ->
            val meta = UriUtil.metadata(context, uri)
            val fileId = UUID.randomUUID().toString()
            upsertTransfer(Transfer(id = fileId, batchId = batchId, direction = TransferDirection.OUTGOING,
                peerName = peer.displayName, peerId = peer.peerId, peerAddress = peer.peerAddress,
                fileName = meta.name, totalBytes = meta.size, state = TransferState.PENDING,
                localPath = uri.toString()))
            Triple(fileId, uri, meta.name)
        }
        persistTransfers()
        sharedUris.value = emptyList()
        scope.launch { prepareAndOffer(batchId, entries, peer) }
    }

    /** Hashes each file (real stream size), registers it as pullable, then offers the manifest. */
    private suspend fun prepareAndOffer(batchId: String, entries: List<Triple<String, Uri, String>>, peer: PeerDevice) {
        val offerFiles = entries.map { (fileId, uri, name) ->
            val (hash, size) = sendClient.hashAndSize(uri)
            config.addOutgoing(fileId, OutgoingSource(uri.toString(), size, hash))
            transfers.update { list -> list.map { if (it.id == fileId) it.copy(sha256 = hash, totalBytes = size) else it } }
            OfferFile(fileId, name, size, hash)
        }
        persistTransfers()
        try {
            sendClient.offer(peer, batchId, offerFiles)
        } catch (e: Exception) {
            entries.forEach { finishTransfer(it.first, TransferState.INTERRUPTED, e.message) }
        }
    }

    /** Sender-side progress, driven by the peer pulling bytes via /pull. */
    private fun updateOutgoingProgress(fileId: String, sent: Long, total: Long) {
        transfers.update { list ->
            list.map {
                if (it.id != fileId) it
                else it.copy(
                    state = if (it.state == TransferState.PENDING || it.state == TransferState.INTERRUPTED) TransferState.ACTIVE else it.state,
                    error = null)
            }
        }
        updateProgress(fileId, sent)
        if (sent >= total) {
            finishTransfer(fileId, TransferState.COMPLETED, null)
            config.removeOutgoing(fileId)
        }
    }

    // MARK: receiving (pull worker)

    private val smallPull = Semaphore(4)
    private val largePull = Semaphore(1)          // large files pulled one-at-a-time
    private val largeThreshold = 64L * 1024 * 1024
    private val pullJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun handleOffer(offer: OfferBody, peer: PeerDevice, senderName: String) {
        val existing = transfers.value.map { it.id }.toSet()
        for (f in offer.files) {
            if (f.fileId in existing) continue
            upsertTransfer(Transfer(id = f.fileId, batchId = offer.batchId, direction = TransferDirection.INCOMING,
                peerName = peer.displayName, peerId = peer.peerId, peerAddress = peer.peerAddress,
                fileName = f.name, totalBytes = f.size, sha256 = f.sha256, state = TransferState.PENDING))
        }
        selectedTab.value = 1
        persistTransfers()
        schedulePulls()
    }

    /** Starts a pull job for every incomplete incoming file not already running. */
    fun schedulePulls() {
        for (t in transfers.value) {
            if (t.direction != TransferDirection.INCOMING || !incomplete(t.state) || pullJobs.containsKey(t.id)) continue
            val id = t.id
            transfers.update { list -> list.map { if (it.id == id && it.state != TransferState.ACTIVE) it.copy(state = TransferState.QUEUED) else it } }
            pullJobs[id] = scope.launch { pullWithRetry(id) }
        }
    }

    private fun incomplete(s: TransferState) =
        s == TransferState.PENDING || s == TransferState.QUEUED || s == TransferState.ACTIVE ||
            s == TransferState.INTERRUPTED || s == TransferState.FAILED

    /** Pulls one file, retrying with backoff (resuming from the .part) until it completes or
     *  is cancelled — the receiver owns "done", so it never gives up silently. */
    private suspend fun pullWithRetry(id: String) {
        val t0 = transfers.value.firstOrNull { it.id == id }
        if (t0 == null) { pullJobs.remove(id); return }
        val limiter = if (t0.totalBytes >= largeThreshold) largePull else smallPull
        try {
            limiter.withPermit {
                var backoff = 1000L
                while (true) {
                    val t = transfers.value.firstOrNull { it.id == id } ?: return@withPermit
                    if (t.state == TransferState.COMPLETED) return@withPermit
                    val peer = peers.value.firstOrNull { it.peerId == t.peerId }
                    if (peer == null) {
                        finishTransfer(id, TransferState.INTERRUPTED, str(S.connectionLost, language.value)); return@withPermit
                    }
                    markActive(id)
                    try {
                        val folderName = peer.localName?.takeIf { it.isNotBlank() } ?: t.peerName
                        val saved = sendClient.pullFile(peer, t.batchId, id, t.fileName, t.totalBytes, t.sha256, folderName) { sent ->
                            updateProgress(id, sent)
                        }
                        finishTransfer(id, TransferState.COMPLETED, null, saved)
                        return@withPermit
                    } catch (e: CancellationException) {
                        finishTransfer(id, TransferState.INTERRUPTED, str(S.canceled, language.value)); throw e
                    } catch (e: Exception) {
                        setRetrying(id)
                        try { delay(backoff) } catch (e: CancellationException) {
                            finishTransfer(id, TransferState.INTERRUPTED, str(S.canceled, language.value)); throw e
                        }
                        backoff = minOf(backoff * 2, 30_000L)   // cap 30s
                    }
                }
            }
        } finally {
            pullJobs.remove(id)
        }
    }

    /** Re-registers outgoing sources and resumes incoming pulls after a restart. */
    private fun restoreTransfers() {
        for (t in transfers.value) {
            if (t.direction == TransferDirection.OUTGOING && t.state != TransferState.COMPLETED && t.state != TransferState.FAILED) {
                val uri = t.localPath
                if (uri != null && t.sha256.isNotEmpty()) config.addOutgoing(t.id, OutgoingSource(uri, t.totalBytes, t.sha256))
            }
        }
        schedulePulls()
    }

    private fun setRetrying(id: String) {
        transfers.update { list ->
            list.map { if (it.id == id) it.copy(error = str(S.retrying, language.value)) else it }
        }
        clearRate(id)
    }

    private fun markActive(id: String) {
        transfers.update { list ->
            list.map { if (it.id == id && it.state != TransferState.ACTIVE && it.state != TransferState.COMPLETED) it.copy(state = TransferState.ACTIVE, error = null) else it }
        }
    }

    /** Cancels a transfer. Incoming: stops the pull (resumable). Outgoing: stops serving it. */
    fun cancelTransfer(transfer: Transfer) {
        if (transfer.direction == TransferDirection.INCOMING) {
            pullJobs[transfer.id]?.cancel()
        } else {
            config.removeOutgoing(transfer.id)
            finishTransfer(transfer.id, TransferState.INTERRUPTED, str(S.canceled, language.value))
        }
    }

    /** Resumes a transfer. Incoming: re-queue the pull. Outgoing: re-arm serving. */
    fun resumeTransfer(transfer: Transfer) {
        if (transfer.direction == TransferDirection.INCOMING) {
            transfers.update { list -> list.map { if (it.id == transfer.id) it.copy(state = TransferState.PENDING, error = null) else it } }
            schedulePulls()
        } else {
            val uri = transfer.localPath
            if (uri != null && transfer.sha256.isNotEmpty()) {
                config.addOutgoing(transfer.id, OutgoingSource(uri, transfer.totalBytes, transfer.sha256))
                transfers.update { list -> list.map { if (it.id == transfer.id) it.copy(state = TransferState.PENDING, error = null) else it } }
            }
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
        transfers.update { cur ->
            val list = cur.toMutableList()
            val i = list.indexOfFirst { it.id == t.id }
            if (i >= 0) list[i] = t else list.add(0, t)
            list
        }
    }

    private fun updateProgress(id: String, bytes: Long) {
        transfers.update { list -> list.map { if (it.id == id) it.copy(transferredBytes = bytes) else it } }
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
        transfers.update { list ->
            list.map {
                if (it.id != id) it
                else it.copy(state = state, error = error,
                    localPath = savedPath ?: it.localPath,
                    transferredBytes = if (state == TransferState.COMPLETED) it.totalBytes else it.transferredBytes)
            }
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
        // Persist the whole list — incoming pulls and outgoing offers must survive a restart.
        store.saveTransfers(transfers.value)
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
