package com.jaewonbaek.wgfilesender.model

import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1
const val DEFAULT_PORT = 51900

// MARK: wire payloads (mirror PROTOCOL.md)

@Serializable
data class PingResponse(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val protocol: Int
)

@Serializable
data class PairRequestBody(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val sessionId: String,
    val pin: String,
    val port: Int
)

@Serializable
data class PairAcceptResponse(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val token: String
)

@Serializable
data class PairConfirmBody(
    val deviceId: String,
    val token: String
)

@Serializable
data class SendStatusResponse(
    val transferId: String,
    val received: Long
)

// MARK: domain / persisted

@Serializable
data class Identity(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android"
)

@Serializable
data class PeerDevice(
    val peerId: String,
    val peerName: String,
    val localName: String? = null,
    val peerAddress: String,
    val tokenOut: String,
    val tokenIn: String,
    val lastSeen: Long? = null
) {
    val displayName: String get() = localName?.takeIf { it.isNotBlank() } ?: peerName
}

@Serializable
data class Settings(
    val downloadTreeUri: String? = null,
    val port: Int = DEFAULT_PORT
)

@Serializable
enum class TransferDirection { INCOMING, OUTGOING }

@Serializable
enum class TransferState { QUEUED, ACTIVE, COMPLETED, FAILED, INTERRUPTED }

@Serializable
data class Transfer(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0,
    val state: TransferState = TransferState.ACTIVE,
    val error: String? = null,
    val localPath: String? = null,  // content uri of the sent source / received file
    val peerId: String = ""         // for resending an outgoing transfer
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
