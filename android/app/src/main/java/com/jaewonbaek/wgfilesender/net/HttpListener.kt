package com.jaewonbaek.wgfilesender.net

import android.content.Context
import android.net.Uri
import com.jaewonbaek.wgfilesender.data.SharedConfig
import com.jaewonbaek.wgfilesender.model.OfferBody
import com.jaewonbaek.wgfilesender.model.PROTOCOL_VERSION
import com.jaewonbaek.wgfilesender.model.PairAcceptResponse
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PingResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream

/** Owns the HTTP listener and routes requests per PROTOCOL.md (pull model). */
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
                    if (peer.peerId != body.deviceId) return@post call.respond(HttpStatusCode.Forbidden)
                    events.onPairConfirm(body)
                    call.respond(HttpStatusCode.NoContent)
                }

                // Receiver side: accept a batch manifest, let the coordinator start pulling.
                post("/offer") {
                    val peer = bearer(call)?.let { config.peerByToken(it) }
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val offer = runCatching { json.decodeFromString<OfferBody>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    if (offer.batchId.isEmpty() || offer.files.size > 10_000 ||
                        offer.files.any { !validId(it.fileId) }) {
                        return@post call.respond(HttpStatusCode.BadRequest)
                    }
                    val senderName = call.request.headers["X-WGFS-Device-Name"] ?: peer.peerName
                    events.onOffer(offer, peer, senderName)
                    call.respond(HttpStatusCode.OK)
                }

                // Sender side: stream a file's bytes (resumable via Range) for the peer to pull.
                get("/pull") {
                    val peer = bearer(call)?.let { config.peerByToken(it) }
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    val fileId = call.request.queryParameters["file"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    if (!validId(fileId)) return@get call.respond(HttpStatusCode.BadRequest)
                    val src = config.outgoingSource(fileId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val total = src.size
                    val start = (rangeStart(call.request.headers[HttpHeaders.Range]) ?: 0L).coerceAtLeast(0L)
                    if (start > total) return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                    val status = if (start > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK
                    if (start > 0) call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-${total - 1}/$total")
                    val uri = Uri.parse(src.uri)
                    call.respondOutputStream(ContentType.Application.OctetStream, status) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            if (start > 0) skipFully(input, start)
                            val buf = ByteArray(1 shl 16)
                            var sent = start
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                write(buf, 0, n)
                                sent += n
                                events.onPullProgress(fileId, sent, total)
                            }
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(500, 1500)
        server = null
    }

    fun restart(port: Int) { stop(); start(port) }

    private fun bearer(call: ApplicationCall): String? {
        val a = call.request.headers["Authorization"] ?: return null
        return if (a.startsWith("Bearer ", ignoreCase = true)) a.substring(7) else null
    }

    /** UUID-shaped ids only (hex + hyphen) — these index file paths on the receiver. */
    private fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    /** Start byte of a `bytes=<start>-` Range header. */
    private fun rangeStart(header: String?): Long? {
        if (header == null) return null
        return header.lowercase().removePrefix("bytes=").substringBefore('-').trim().toLongOrNull()
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(1 shl 16)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) break
            remaining -= r
        }
    }
}
