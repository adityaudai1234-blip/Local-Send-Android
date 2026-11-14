package com.example.data.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

class TransferNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_TRANSFERS = "channel_transfers"
        const val CHANNEL_REQUESTS = "channel_incoming_requests"

        const val GROUP_TRANSFERS = "com.example.localdrop.TRANSFERS_GROUP"
        const val NOTIFICATION_ID_GROUP_SUMMARY = 1001
        const val NOTIFICATION_ID_PROGRESS_BASE = 1000
        const val NOTIFICATION_ID_REQUEST = 999
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress and completion status of file transfers"
            }

            val requestChannel = NotificationChannel(
                CHANNEL_REQUESTS,
                "Incoming Transfer Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when nearby peers request to send files"
                enableVibration(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(transferChannel)
            manager?.createNotificationChannel(requestChannel)
        }
    }

    private fun createOpenAppPendingIntent(requestCode: Int = 0): PendingIntent {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showIncomingTransferRequest(request: IncomingTransferRequest) {
        try {
            val acceptIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
                action = TransferNotificationReceiver.ACTION_ACCEPT_TRANSFER
                putExtra(TransferNotificationReceiver.EXTRA_SESSION_ID, request.sessionId)
            }
            val acceptPending = PendingIntent.getBroadcast(
                context,
                request.sessionId.hashCode(),
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
                action = TransferNotificationReceiver.ACTION_REJECT_TRANSFER
                putExtra(TransferNotificationReceiver.EXTRA_SESSION_ID, request.sessionId)
            }
            val declinePending = PendingIntent.getBroadcast(
                context,
                request.sessionId.hashCode() + 1,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Incoming transfer from ${request.senderName}")
                .setContentText("${request.files.size} file(s) • ${request.formattedTotalBytes}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(createOpenAppPendingIntent(NOTIFICATION_ID_REQUEST))
                .addAction(0, "Accept", acceptPending)
                .addAction(0, "Decline", declinePending)

            notificationManager.notify(NOTIFICATION_ID_REQUEST, builder.build())
        } catch (_: SecurityException) {}
    }

    fun cancelIncomingRequestNotification() {
        cancelNotification(NOTIFICATION_ID_REQUEST)
    }

    fun showTransferProgress(
        notificationId: Int,
        transferId: String,
        fileName: String,
        progressPercent: Int,
        speedStr: String,
        activeCount: Int = 1
    ) {
        try {
            val cancelIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
                action = TransferNotificationReceiver.ACTION_CANCEL_TRANSFER
                putExtra(TransferNotificationReceiver.EXTRA_TRANSFER_ID, transferId)
            }
            val cancelPending = PendingIntent.getBroadcast(
                context,
                notificationId,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(fileName)
                .setContentText("$progressPercent% • $speedStr")
                .setProgress(100, progressPercent, false)
                .setOngoing(true)
                .setGroup(GROUP_TRANSFERS)
                .setContentIntent(createOpenAppPendingIntent(notificationId))
                .setOnlyAlertOnce(true)
                .addAction(0, "Cancel", cancelPending)

            notificationManager.notify(notificationId, builder.build())

            if (activeCount > 1) {
                val summary = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("LocalDrop Transfers")
                    .setContentText("$activeCount active file transfers")
                    .setGroup(GROUP_TRANSFERS)
                    .setGroupSummary(true)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .build()
                notificationManager.notify(NOTIFICATION_ID_GROUP_SUMMARY, summary)
            }
        } catch (_: SecurityException) {}
    }

    fun showTransferComplete(notificationId: Int, fileName: String, totalSize: String, isSend: Boolean = false) {
        try {
            val verb = if (isSend) "sent" else "received"
            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transfer Complete")
                .setContentText("$fileName ($totalSize) $verb successfully")
                .setGroup(GROUP_TRANSFERS)
                .setAutoCancel(true)
                .setContentIntent(createOpenAppPendingIntent(notificationId))

            notificationManager.notify(notificationId, builder.build())
            notificationManager.cancel(NOTIFICATION_ID_GROUP_SUMMARY)
        } catch (_: SecurityException) {}
    }

    fun showTransferFailed(
        notificationId: Int,
        transferId: String,
        fileName: String,
        reason: String,
        canRetry: Boolean = false
    ) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transfer Failed")
                .setContentText("$fileName: $reason")
                .setGroup(GROUP_TRANSFERS)
                .setAutoCancel(true)
                .setContentIntent(createOpenAppPendingIntent(notificationId))

            if (canRetry) {
                val retryIntent = Intent(context, TransferNotificationReceiver::class.java).apply {
                    action = TransferNotificationReceiver.ACTION_RETRY_TRANSFER
                    putExtra(TransferNotificationReceiver.EXTRA_TRANSFER_ID, transferId)
                }
                val retryPending = PendingIntent.getBroadcast(
                    context,
                    notificationId + 2,
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Retry", retryPending)
            }

            notificationManager.notify(notificationId, builder.build())
            notificationManager.cancel(NOTIFICATION_ID_GROUP_SUMMARY)
        } catch (_: SecurityException) {}
    }

    fun cancelNotification(notificationId: Int) {
        try {
            notificationManager.cancel(notificationId)
        } catch (_: Exception) {}
    }
}
