package com.example.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.example.LocalDropApp
import com.example.MainActivity
import com.example.data.network.ConnectionState
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class WidgetSnapshot(
    val nearbyCount: Int = 0,
    val connectedCount: Int = 0,
    val isScanning: Boolean = false,
    val activeTransfers: Int = 0,
    val overallProgressPercent: Int = 0,
    val completedTransfers: Int = 0,
    val totalBytesText: String = "0 B",
    val quickSendTargetName: String? = null,
    val quickSendTargetId: String? = null
)

object WidgetUpdateHelper {
    const val EXTRA_WIDGET_ACTION = "com.example.WIDGET_ACTION"
    const val EXTRA_TARGET_DEVICE_ID = "com.example.WIDGET_TARGET_DEVICE_ID"

    const val ACTION_VIEW_DEVICES = "view_devices"
    const val ACTION_QUICK_SEND = "quick_send"
    const val ACTION_OPEN_PICKER = "open_picker"
    const val ACTION_VIEW_TRANSFERS = "view_transfers"

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null

    fun createLaunchIntent(context: Context, action: String, targetDeviceId: String? = null): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WIDGET_ACTION, action)
            if (!targetDeviceId.isNullOrBlank()) {
                putExtra(EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            }
        }
    }

    suspend fun computeSnapshot(context: Context): WidgetSnapshot {
        val app = context.applicationContext as? LocalDropApp ?: return WidgetSnapshot()
        val container = app.container

        val discovered = container.deviceDiscoveryRepository.discoveredDevices.value
        val paired = container.pairedDeviceRepository.pairedDevices.firstOrNull().orEmpty()
        val connStates = container.connectionManager.connectionStates.value
        val connectedCount = connStates.values.count { it is ConnectionState.Connected }
        val isScanning = container.deviceDiscoveryRepository.isScanning.value

        val activeList = container.transferManager.activeTransfers.value
        val running = activeList.filter { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
        val overallPercent = if (running.isEmpty()) {
            0
        } else {
            ((running.map { it.progress }.average()) * 100.0).toInt().coerceIn(0, 100)
        }
        val stats = container.transferManager.stats.value

        val settings = container.settingsRepository.settings.value
        val preferredPeer = paired.firstOrNull { it.id == settings.lastDestinationDeviceId }
            ?: paired.firstOrNull { it.isFavorite }
            ?: paired.maxByOrNull { maxOf(it.lastTransferredAt, it.lastConnectedAt) }
        val nearbyFallback = discovered.firstOrNull()

        return WidgetSnapshot(
            nearbyCount = discovered.size,
            connectedCount = connectedCount,
            isScanning = isScanning,
            activeTransfers = stats.activeCount,
            overallProgressPercent = overallPercent,
            completedTransfers = stats.completedCount,
            totalBytesText = TransferItem.formatBytes(stats.totalBytesTransferred),
            quickSendTargetName = preferredPeer?.alias ?: nearbyFallback?.alias,
            quickSendTargetId = preferredPeer?.id ?: nearbyFallback?.id
        )
    }

    fun requestUpdateAll(context: Context) {
        debounceJob?.cancel()
        debounceJob = updateScope.launch {
            delay(350L)
            try {
                DeviceStatusWidget().updateAll(context)
                QuickSendWidget().updateAll(context)
                TransferStatusWidget().updateAll(context)
            } catch (_: Exception) {
                // Glance host unavailable in unit tests or headless startup
            }
        }
    }
}
