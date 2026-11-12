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
                description = "Shows real-time progress of active file transfers"
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

    fun showTransferProgress(
        notificationId: Int,
        fileName: String,
        progressPercent: Int,
        speedStr: String
    ) {
        try {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(fileName)
                .setContentText("$progressPercent% • $speedStr")
                .setProgress(100, progressPercent, false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)

            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Notifications permission not granted
        }
    }

    fun showTransferComplete(notificationId: Int, fileName: String, totalSize: String) {
        try {
            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transfer Complete")
                .setContentText("$fileName ($totalSize) received successfully")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun showTransferFailed(notificationId: Int, fileName: String, reason: String) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Transfer Failed")
                .setContentText("$fileName: $reason")
                .setAutoCancel(true)

            notificationManager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun cancelNotification(notificationId: Int) {
        try {
            notificationManager.cancel(notificationId)
        } catch (_: Exception) {}
    }
}
