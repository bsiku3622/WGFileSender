package com.jaewonbaek.wgfilesender.net

import com.jaewonbaek.wgfilesender.model.PairConfirmBody
import com.jaewonbaek.wgfilesender.model.PairRequestBody
import com.jaewonbaek.wgfilesender.model.Transfer
import com.jaewonbaek.wgfilesender.model.TransferState

/** Callbacks the listener fires up to the coordinator/UI layer. */
interface ListenerEvents {
    /** Show the accept prompt and suspend until the user decides.
     *  Returns the token THIS device issues for the peer (its tokenIn), or null if declined. */
    suspend fun onPairRequest(body: PairRequestBody, address: String): String?
    fun onPairConfirm(body: PairConfirmBody)
    fun onTransferStart(transfer: Transfer)
    fun onTransferProgress(id: String, bytes: Long)
    fun onTransferFinish(id: String, state: TransferState, error: String?)
}

class PairDeclinedException : Exception("The other device declined")
