package com.example.data.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.LocalDropApp

class TransferNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT_TRANSFER = "com.example.localdrop.action.ACCEPT_TRANSFER"
        const val ACTION_REJECT_TRANSFER = "com.example.localdrop.action.REJECT_TRANSFER"
        const val ACTION_CANCEL_TRANSFER = "com.example.localdrop.action.CANCEL_TRANSFER"
        const val ACTION_RETRY_TRANSFER = "com.example.localdrop.action.RETRY_TRANSFER"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? LocalDropApp ?: return
        val transferManager = app.container.transferManager
        val notificationHelper = app.container.transferNotificationHelper

        when (action) {
            ACTION_ACCEPT_TRANSFER -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                val pending = transferManager.incomingRequest.value
                if (pending != null && pending.sessionId == sessionId) {
                    transferManager.acceptIncomingTransfer(pending)
                }
                notificationHelper.cancelIncomingRequestNotification()
            }
            ACTION_REJECT_TRANSFER -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                val pending = transferManager.incomingRequest.value
                if (pending != null && pending.sessionId == sessionId) {
                    transferManager.rejectIncomingTransfer(pending)
                }
                notificationHelper.cancelIncomingRequestNotification()
            }
            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
                transferManager.cancelTransfer(transferId)
            }
            ACTION_RETRY_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
                transferManager.retryFailedTransfer(transferId)
                notificationHelper.cancelNotification(transferId.hashCode())
            }
        }
    }
}
