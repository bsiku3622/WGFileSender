package com.jaewonbaek.wgfilesender.net

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jaewonbaek.wgfilesender.data.SharedConfig
import com.jaewonbaek.wgfilesender.model.OfferBody
import com.jaewonbaek.wgfilesender.model.OfferFile
import com.jaewonbaek.wgfilesender.model.PairAcceptResponse
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.PingResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class PullIncompleteException : Exception("Connection ended early")
class ChecksumException : Exception("Checksum mismatch")

/** Outbound side: probes, pairing, announcing a manifest (/offer), and — as the receiver —
 *  pulling file bytes (/pull) with resume + hash verification. */
class SendClient(private val context: Context, private val config: SharedConfig) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(CIO) { install(HttpTimeout) }

    suspend fun ping(address: String): PingResponse {
        val resp = client.get("http://$address/ping")
        check(resp.status.value == 200) { "ping ${resp.status.value}" }
        return json.decodeFromString(resp.bodyAsText())
    }

    suspend fun requestPair(address: String, sessionId: String, pin: String): PairAcceptResponse {
        val id = config.identity
        val body = PairRequestBody(id.deviceId, id.deviceName, id.platform, sessionId, pin, config.settings.port)
        val resp = client.post("http://$address/pair/request") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
            timeout { requestTimeoutMillis = 90_000 }
        }
        if (resp.status.value == 403) throw PairDeclinedException()
        check(resp.status.value == 200) { "pair ${resp.status.value}" }
        return json.decodeFromString(resp.bodyAsText())
    }

    suspend fun confirmPair(address: String, tokenOut: String, ourTokenForPeer: String) {
        val body = PairConfirmBody(config.identity.deviceId, ourTokenForPeer)
        val resp = client.post("http://$address/pair/confirm") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $tokenOut")
            setBody(json.encodeToString(body))
        }
        check(resp.status.value == 204) { "confirm ${resp.status.value}" }
    }

    // MARK: sender — announce a batch manifest

    suspend fun offer(peer: PeerDevice, batchId: String, files: List<OfferFile>) {
        val id = config.identity
        val resp = client.post("http://${peer.peerAddress}/offer") {
            header(HttpHeaders.Authorization, "Bearer ${peer.tokenOut}")
            header("X-WGFS-Device-Id", id.deviceId)
            header("X-WGFS-Device-Name", id.deviceName)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OfferBody(batchId, files)))
            timeout { requestTimeoutMillis = 30_000 }
        }
        check(resp.status.value == 200) { "offer ${resp.status.value}" }
    }

    /** Hashes and measures a content-uri in one pass (for building the manifest). */
    fun hashAndSize(uri: Uri): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }

    // MARK: receiver — pull a file's bytes, resuming and verifying

    /** Pulls (or resumes) one file into the SAF tree and returns the saved uri. Throws
     *  [PullIncompleteException] (keep .part, retry) or [ChecksumException] (corrupt). */
    suspend fun pullFile(
        peer: PeerDevice, batchId: String, fileId: String, fileName: String,
        expectedSize: Long, expectedHash: String, senderFolder: String,
        onProgress: (Long) -> Unit
    ): String {
        val part = File(context.cacheDir, "$fileId.part")
        val start = if (part.exists()) part.length() else 0L

        if (start < expectedSize || start == 0L) {
            client.prepareGet("http://${peer.peerAddress}/pull?batch=$batchId&file=$fileId") {
                if (start > 0) header(HttpHeaders.Range, "bytes=$start-")
            }.execute { resp ->
                if (resp.status.value !in 200..299) throw IOException("pull ${resp.status.value}")
                val channel = resp.bodyAsChannel()
                withContext(Dispatchers.IO) {
                    FileOutputStream(part, /* append = */ start > 0L).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var received = start
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n == -1) break
                            if (n > 0) {
                                out.write(buf, 0, n)
                                received += n
                                onProgress(received)
                            }
                        }
                    }
                }
            }
        }

        if (part.length() < expectedSize) throw PullIncompleteException()
        val digest = sha256(part)
        if (digest != expectedHash.lowercase()) {
            part.delete()   // corrupt — start over next round
            throw ChecksumException()
        }
        val saved = saveToTree(senderFolder, fileName, part)
            ?: throw IOException("no download folder")
        part.delete()
        return saved
    }

    private fun saveToTree(folderName: String, fileName: String, source: File): String? {
        val treeUriStr = config.settings.downloadTreeUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        val safeFolder = folderName.replace('/', '_').ifBlank { "Unknown" }
        val folder = tree.findFile(safeFolder)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(safeFolder) ?: return null
        val safeName = fileName.replace('/', '_').replace("..", "_").ifBlank { "file" }
        val ext = safeName.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
        val target = folder.createFile(mime, safeName) ?: return null
        val out = context.contentResolver.openOutputStream(target.uri) ?: return null
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
        return target.uri.toString()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
