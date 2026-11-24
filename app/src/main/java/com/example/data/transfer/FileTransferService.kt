package com.example.data.transfer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 1000L

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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            else -> {
                acquireLocks()
                startInForeground()
                observeTransfers()
                return START_STICKY
            }
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDrop:TransferWakeLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {}

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "LocalDrop:TransferWifiLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
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
                val hasActive = list.any { it.status == TransferStatus.ACTIVE || (it.status == TransferStatus.PENDING && !it.isPaused) }
                if (!hasActive) {
                    stopForegroundService()
                }
            }
        }
    }

    private fun stopForegroundService() {
        observationJob?.cancel()
        observationJob = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
        scope.cancel()
    }
}
