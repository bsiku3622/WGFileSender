package com.jaewonbaek.wgfilesender.data

import com.jaewonbaek.wgfilesender.model.Identity
import com.jaewonbaek.wgfilesender.model.PeerDevice
import com.jaewonbaek.wgfilesender.model.Settings
import com.jaewonbaek.wgfilesender.ui.Lang

/** Snapshot the networking layer reads from background threads. */
class SharedConfig(identity: Identity, peers: List<PeerDevice>, settings: Settings) {
    @Volatile var identity: Identity = identity
    @Volatile var peers: List<PeerDevice> = peers
    @Volatile var settings: Settings = settings
    @Volatile var language: Lang = Lang.EN

    /** Peer presenting [token] matches the token THIS device issued (tokenIn). */
    fun peerByToken(token: String): PeerDevice? = peers.firstOrNull { it.tokenIn == token }
    fun peerById(id: String): PeerDevice? = peers.firstOrNull { it.peerId == id }
}
