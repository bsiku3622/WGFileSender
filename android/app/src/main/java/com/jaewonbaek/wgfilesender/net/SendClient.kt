package com.jaewonbaek.wgfilesender.net

import android.content.Context
import android.net.Uri
import com.jaewonbaek.wgfilesender.data.SharedConfig
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
        size: Long,
        transferId: String,
        onProgress: (Long) -> Unit
    ) {
        val hash = sha256(uri)
        val id = config.identity
        val resolver = context.contentResolver

        val content = object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long = size
            override suspend fun writeTo(channel: ByteWriteChannel) {
                val buf = ByteArray(1 shl 16)
                var sent = 0L
                resolver.openInputStream(uri)?.use { input ->
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
            header("X-WGFS-File-Size", size.toString())
            header("X-WGFS-Sha256", hash)
            header("X-WGFS-Transfer-Id", transferId)
            setBody(content)
        }
        check(resp.status.value == 200) { "send ${resp.status.value}" }
    }

    private fun sha256(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
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
