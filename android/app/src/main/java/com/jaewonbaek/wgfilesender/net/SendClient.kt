package com.jaewonbaek.wgfilesender.net

import android.content.Context
import android.net.Uri
import com.jaewonbaek.wgfilesender.data.SharedConfig
import com.jaewonbaek.wgfilesender.model.PairAcceptResponse
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.PingResponse
import com.jaewonbaek.wgfilesender.model.SendStatusResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest

/** Outbound side: probes, pairing handshake, and streaming uploads. */
class SendClient(private val context: Context, private val config: SharedConfig) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // No global request timeout (uploads can be large); pairing sets its own per-request.
    private val client = HttpClient(CIO) {
        install(HttpTimeout)
    }

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
            timeout { requestTimeoutMillis = 90_000 }  // waits for the human to accept
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

    suspend fun sendFile(
        peer: PeerDevice,
        uri: Uri,
        fileName: String,
        transferId: String,
        tryResume: Boolean = false,
        onSize: (Long) -> Unit = {},
        onProgress: (Long) -> Unit
    ) {
        // Trust the stream, not the SAF/MediaStore metadata: hash and measure in one pass so
        // Content-Length always matches the bytes the hash covers (a wrong reported size was
        // truncating receives and failing every checksum).
        val (hash, actualSize) = hashAndSize(uri)
        onSize(actualSize)
        val id = config.identity
        val resolver = context.contentResolver

        // On a resume attempt, ask the receiver how many bytes it already holds and send
        // only the remainder. First-time sends skip the round-trip and stream the whole file.
        var offset = 0L
        if (tryResume) {
            val have = runCatching { sendStatus(peer, transferId) }.getOrDefault(0L)
            if (have in 1 until actualSize) offset = have
        }

        val content = object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long = actualSize - offset
            override suspend fun writeTo(channel: ByteWriteChannel) {
                val buf = ByteArray(1 shl 16)
                var sent = offset
                resolver.openInputStream(uri)?.use { input ->
                    if (offset > 0) skipFully(input, offset)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        channel.writeFully(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }

        val resp = client.post("http://${peer.peerAddress}/send") {
            header(HttpHeaders.Authorization, "Bearer ${peer.tokenOut}")
            header("X-WGFS-Device-Id", id.deviceId)
            header("X-WGFS-Device-Name", id.deviceName)
            header("X-WGFS-File-Name", Uri.encode(fileName))
            header("X-WGFS-File-Size", actualSize.toString())
            header("X-WGFS-Sha256", hash)
            header("X-WGFS-Transfer-Id", transferId)
            if (offset > 0) header("Content-Range", "bytes $offset-${actualSize - 1}/$actualSize")
            setBody(content)
        }
        check(resp.status.value == 200) { "send ${resp.status.value}" }
    }

    /** Bytes the receiver already has for this transfer (0 if none / unreachable). */
    private suspend fun sendStatus(peer: PeerDevice, transferId: String): Long {
        val resp = client.get("http://${peer.peerAddress}/send/status?transferId=$transferId") {
            header(HttpHeaders.Authorization, "Bearer ${peer.tokenOut}")
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (resp.status.value != 200) return 0L
        return json.decodeFromString<SendStatusResponse>(resp.bodyAsText()).received
    }

    /** Discards `n` bytes from the stream (ContentResolver streams don't reliably skip()). */
    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(1 shl 16)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            remaining -= r
        }
    }

    /** Hashes and measures the stream in one pass so both describe the exact same bytes. */
    private fun hashAndSize(uri: Uri): Pair<String, Long> {
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
}
