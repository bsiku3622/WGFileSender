package com.jaewonbaek.wgfilesender.net

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jaewonbaek.wgfilesender.data.SharedConfig
import com.jaewonbaek.wgfilesender.model.PROTOCOL_VERSION
import com.jaewonbaek.wgfilesender.model.PairAcceptResponse
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PingResponse
import com.jaewonbaek.wgfilesender.model.SendStatusResponse
import com.jaewonbaek.wgfilesender.model.Transfer
import com.jaewonbaek.wgfilesender.model.TransferDirection
import com.jaewonbaek.wgfilesender.model.TransferState
import com.jaewonbaek.wgfilesender.ui.S
import com.jaewonbaek.wgfilesender.ui.str
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Owns the HTTP listener and routes requests per PROTOCOL.md. */
class HttpListener(
    private val context: Context,
    private val config: SharedConfig,
    private val events: ListenerEvents
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/ping") {
                    val id = config.identity
                    call.respondText(
                        json.encodeToString(PingResponse(id.deviceId, id.deviceName, id.platform, PROTOCOL_VERSION)),
                        ContentType.Application.Json
                    )
                }

                post("/pair/request") {
                    val body = runCatching { json.decodeFromString<PairRequestBody>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val address = "${call.request.origin.remoteHost}:${body.port}"
                    val token = events.onPairRequest(body, address)
                        ?: return@post call.respond(HttpStatusCode.Forbidden)
                    val id = config.identity
                    call.respondText(
                        json.encodeToString(PairAcceptResponse(id.deviceId, id.deviceName, id.platform, token)),
                        ContentType.Application.Json
                    )
                }

                post("/pair/confirm") {
                    val peer = bearer(call)?.let { config.peerByToken(it) }
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val body = runCatching { json.decodeFromString<PairConfirmBody>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    // The caller may only confirm its own pairing, not overwrite another peer's token.
                    if (peer.peerId != body.deviceId) return@post call.respond(HttpStatusCode.Forbidden)
                    events.onPairConfirm(body)
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/send") { handleSend(call) }

                get("/send/status") {
                    val token = bearer(call)
                    if (token == null || config.peerByToken(token) == null)
                        return@get call.respond(HttpStatusCode.Unauthorized)
                    val transferId = call.request.queryParameters["transferId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    if (!validTransferId(transferId)) return@get call.respond(HttpStatusCode.BadRequest)
                    val size = partFile(transferId).let { if (it.exists()) it.length() else 0L }
                    call.respondText(
                        json.encodeToString(SendStatusResponse(transferId, size)),
                        ContentType.Application.Json
                    )
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(500, 1500)
        server = null
    }

    fun restart(port: Int) { stop(); start(port) }

    private suspend fun handleSend(call: ApplicationCall) {
        val token = bearer(call)
        val peer = token?.let { config.peerByToken(it) }
            ?: return call.respond(HttpStatusCode.Unauthorized)
        val rawName = call.request.headers["X-WGFS-File-Name"]
            ?: return call.respond(HttpStatusCode.BadRequest)
        val transferId = call.request.headers["X-WGFS-Transfer-Id"]
            ?: return call.respond(HttpStatusCode.BadRequest)
        if (!validTransferId(transferId)) return call.respond(HttpStatusCode.BadRequest)
        val expected = call.request.headers["X-WGFS-Sha256"]?.lowercase()
            ?: return call.respond(HttpStatusCode.BadRequest)
        val fileName = Uri.decode(rawName)
        // Require a declared size and match it exactly, so a missing/zero header can't pass an
        // empty or truncated body off as complete.
        val total = call.request.headers["X-WGFS-File-Size"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest)
        val senderName = call.request.headers["X-WGFS-Device-Name"] ?: peer.peerName
        val folderName = peer.localName?.takeIf { it.isNotBlank() } ?: senderName

        val part = partFile(transferId)
        // Resume: honor Content-Range only when its start matches the bytes already on disk;
        // otherwise begin a fresh .part.
        val onDisk = if (part.exists()) part.length() else 0L
        val start = rangeStart(call.request.headers["Content-Range"])
            ?.takeIf { it > 0 && it == onDisk } ?: 0L
        if (start == 0L && part.exists()) part.delete()

        val md = MessageDigest.getInstance("SHA-256")
        if (start > 0L) {
            // Prime the hasher with the bytes we're keeping before appending the rest.
            part.inputStream().use { existing ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = existing.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
        }

        events.onTransferStart(
            Transfer(transferId, TransferDirection.INCOMING, peer.displayName, fileName, total,
                transferredBytes = start)
        )

        var received = start
        var complete = false
        // Blocking stream copy off the event loop; hash as we write. A mid-body disconnect
        // throws here — we keep the .part so the sender can resume.
        try {
            withContext(Dispatchers.IO) {
                call.receiveStream().use { input ->
                    FileOutputStream(part, /* append = */ start > 0L).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            received += n
                            events.onTransferProgress(transferId, received)
                        }
                    }
                }
            }
            complete = received == total
        } catch (_: Exception) {
            complete = false
        }

        if (!complete) {
            events.onTransferFinish(transferId, TransferState.INTERRUPTED, str(S.connectionLost, config.language))
            return call.respond(HttpStatusCode.BadRequest)
        }

        val digest = md.digest().joinToString("") { "%02x".format(it) }
        if (digest != expected) {
            part.delete()   // a fully-received file that hashes wrong is corrupt
            events.onTransferFinish(transferId, TransferState.FAILED, str(S.checksumMismatch, config.language))
            return call.respond(HttpStatusCode.Conflict)
        }
        // Saving can throw (storage full, SAF revoked); never let it escape and leave the
        // transfer stuck ACTIVE with an orphaned .part.
        val savedUri = try {
            saveToTree(folderName, fileName, part)
        } catch (_: Exception) {
            null
        }
        part.delete()
        if (savedUri == null) {
            events.onTransferFinish(transferId, TransferState.FAILED, str(S.noDownloadFolder, config.language))
            return call.respond(HttpStatusCode.InternalServerError)
        }
        events.onTransferFinish(transferId, TransferState.COMPLETED, null, savedUri)
        call.respond(HttpStatusCode.OK)
    }

    /** Start byte of a `bytes <start>-<end>/<total>` Content-Range header, or null. */
    private fun rangeStart(header: String?): Long? {
        if (header == null) return null
        return header.lowercase().removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
    }

    // MARK: storage (SAF)

    /** Returns the saved file's content uri, or null on failure. */
    private fun saveToTree(folderName: String, fileName: String, source: File): String? {
        val treeUriStr = config.settings.downloadTreeUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        val safeFolder = folderName.replace('/', '_').ifBlank { "Unknown" }
        val folder = tree.findFile(safeFolder)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(safeFolder) ?: return null
        val safeName = fileName.replace('/', '_').replace("..", "_").ifBlank { "file" }
        val target = newChild(folder, safeName) ?: return null
        val out = context.contentResolver.openOutputStream(target.uri) ?: return null
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
        return target.uri.toString()
    }

    /** Creates the file; the SAF provider auto-uniquifies the name (… (1), (2)) on collision. */
    private fun newChild(folder: DocumentFile, fileName: String): DocumentFile? {
        val ext = fileName.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
        return folder.createFile(mime, fileName)
    }

    private fun partFile(transferId: String) = File(context.cacheDir, "$transferId.part")

    private fun bearer(call: ApplicationCall): String? {
        val a = call.request.headers["Authorization"] ?: return null
        return if (a.startsWith("Bearer ", ignoreCase = true)) a.substring(7) else null
    }

    /** Transfer ids index file paths; accept only UUID-shaped values (hex + hyphen) to
     *  block `../` traversal via the X-WGFS-Transfer-Id header. */
    private fun validTransferId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
}
