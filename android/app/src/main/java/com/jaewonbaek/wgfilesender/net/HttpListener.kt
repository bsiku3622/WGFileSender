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
                    val token = bearer(call)
                    if (token == null || config.peerByToken(token) == null)
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    val body = runCatching { json.decodeFromString<PairConfirmBody>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
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
        val expected = call.request.headers["X-WGFS-Sha256"]?.lowercase()
            ?: return call.respond(HttpStatusCode.BadRequest)
        val fileName = Uri.decode(rawName)
        val total = call.request.headers["X-WGFS-File-Size"]?.toLongOrNull() ?: 0L
        val senderName = call.request.headers["X-WGFS-Device-Name"] ?: peer.peerName
        val folderName = peer.localName?.takeIf { it.isNotBlank() } ?: senderName

        events.onTransferStart(
            Transfer(transferId, TransferDirection.INCOMING, peer.displayName, fileName, total)
        )

        val part = partFile(transferId)
        val md = MessageDigest.getInstance("SHA-256")
        var received = 0L
        // Blocking stream copy off the event loop; hash as we write.
        withContext(Dispatchers.IO) {
            call.receiveStream().use { input ->
                part.outputStream().use { out ->
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

        val digest = md.digest().joinToString("") { "%02x".format(it) }
        if (digest != expected) {
            part.delete()
            events.onTransferFinish(transferId, TransferState.FAILED, str(S.checksumMismatch, config.language))
            return call.respond(HttpStatusCode.Conflict)
        }
        if (!saveToTree(folderName, fileName, part)) {
            part.delete()
            events.onTransferFinish(transferId, TransferState.FAILED, str(S.noDownloadFolder, config.language))
            return call.respond(HttpStatusCode.InternalServerError)
        }
        part.delete()
        events.onTransferFinish(transferId, TransferState.COMPLETED, null)
        call.respond(HttpStatusCode.OK)
    }

    // MARK: storage (SAF)

    private fun saveToTree(folderName: String, fileName: String, source: File): Boolean {
        val treeUriStr = config.settings.downloadTreeUri ?: return false
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return false
        val safeFolder = folderName.replace('/', '_').ifBlank { "Unknown" }
        val folder = tree.findFile(safeFolder)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(safeFolder) ?: return false
        val target = uniqueChild(folder, fileName) ?: return false
        val out = context.contentResolver.openOutputStream(target.uri) ?: return false
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
        return true
    }

    private fun uniqueChild(folder: DocumentFile, fileName: String): DocumentFile? {
        val ext = fileName.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
        if (folder.findFile(fileName) == null) return folder.createFile(mime, fileName)
        val stem = if (ext.isEmpty()) fileName else fileName.substring(0, fileName.length - ext.length - 1)
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            if (folder.findFile(candidate) == null) return folder.createFile(mime, candidate)
            n++
        }
    }

    private fun partFile(transferId: String) = File(context.cacheDir, "$transferId.part")

    private fun bearer(call: ApplicationCall): String? {
        val a = call.request.headers["Authorization"] ?: return null
        return if (a.startsWith("Bearer ", ignoreCase = true)) a.substring(7) else null
    }
}
