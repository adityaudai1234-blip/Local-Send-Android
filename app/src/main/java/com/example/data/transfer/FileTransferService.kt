package com.example.data.transfer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.LocalDropApp
import com.example.R
import com.example.domain.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FileTransferService : Service() {

    companion object {
        const val NOTIFICATION_ID = 9001
        const val ACTION_START = "com.example.localdrop.action.START_TRANSFER"
        const val ACTION_STOP = "com.example.localdrop.action.STOP_TRANSFER"

        fun start(context: Context) {
            val intent = Intent(context, FileTransferService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, FileTransferService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                observeTransfers()
                return START_STICKY
            }
        }
    }

    private fun startInForeground() {
        val notificationHelper = TransferNotificationHelper(applicationContext)
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            TransferNotificationHelper.CHANNEL_TRANSFERS
        )
            .setContentTitle("LocalDrop File Sharing")
            .setContentText("Transferring files over local network…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeTransfers() {
        observationJob?.cancel()
        val app = application as? LocalDropApp ?: return
        val transferManager = app.container.transferManager

        observationJob = scope.launch {
            transferManager.activeTransfers.collect { list ->
                val hasActive = list.any { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
                if (!hasActive) {
                    // All active transfers completed, stop service
                    stopForegroundService()
                }
            }
        }
    }

    private fun stopForegroundService() {
        observationJob?.cancel()
        observationJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
