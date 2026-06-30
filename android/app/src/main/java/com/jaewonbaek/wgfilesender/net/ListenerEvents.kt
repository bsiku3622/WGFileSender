package com.jaewonbaek.wgfilesender.net

import com.jaewonbaek.wgfilesender.model.OfferBody
import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.PeerDevice

/** Callbacks the listener fires up to the coordinator/UI layer (pull model). */
interface ListenerEvents {
    /** Show the accept prompt and suspend until the user decides.
     *  Returns the token THIS device issues for the peer (its tokenIn), or null if declined. */
    suspend fun onPairRequest(body: PairRequestBody, address: String): String?
    fun onPairConfirm(body: PairConfirmBody)
    /** Peer offered a batch manifest; we (the receiver) persist it and start pulling. */
    fun onOffer(offer: OfferBody, peer: PeerDevice, senderName: String)
    /** Bytes served so far for an outgoing file via /pull (sender-side progress). */
    fun onPullProgress(fileId: String, sent: Long, total: Long)
}

class PairDeclinedException : Exception("The other device declined")
