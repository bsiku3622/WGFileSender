package com.jaewonbaek.wgfilesender.data

import com.jaewonbaek.wgfilesender.model.Identity
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Settings
import com.jaewonbaek.wgfilesender.ui.Lang
import java.util.concurrent.ConcurrentHashMap

/** A file this device is offering to a peer, looked up by fileId when serving /pull. */
data class OutgoingSource(val uri: String, val size: Long, val sha256: String)

/** Snapshot the networking layer reads from background threads. */
class SharedConfig(identity: Identity, peers: List<PeerDevice>, settings: Settings) {
    @Volatile var identity: Identity = identity
    @Volatile var peers: List<PeerDevice> = peers
    @Volatile var settings: Settings = settings
    @Volatile var language: Lang = Lang.EN

    private val outgoing = ConcurrentHashMap<String, OutgoingSource>()   // fileId -> source

    /** Peer presenting [token] matches the token THIS device issued (tokenIn). */
    fun peerByToken(token: String): PeerDevice? = peers.firstOrNull { it.tokenIn == token }
    fun peerById(id: String): PeerDevice? = peers.firstOrNull { it.peerId == id }

    fun outgoingSource(fileId: String): OutgoingSource? = outgoing[fileId]
    fun addOutgoing(fileId: String, src: OutgoingSource) { outgoing[fileId] = src }
    fun removeOutgoing(fileId: String) { outgoing.remove(fileId) }
}
