package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.ErrorCategory
import com.example.util.LocalCrashReporter
import com.example.widget.WidgetUpdateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LocalDropApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LocalCrashReporter.install(this)
        container = DefaultAppContainer(this)

        // Startup queue recovery & orphan temp file maintenance (preserves resumable partials)
        appScope.launch {
            try {
                val recoverableIds = container.transferManager.recoverInterruptedTransfers()
                val currentSettings = container.settingsRepository.settings.first()
                if (currentSettings.autoCleanTempFiles) {
                    container.storageManager.cleanOrphanTempFiles(recoverableIds)
                } else {
                    container.storageManager.cleanStaleTempFiles()
                }
            } catch (e: Exception) {
                AppErrorReporter.reportThrowable(
                    category = ErrorCategory.STORAGE,
                    throwable = e,
                    targetLabel = "Startup Recovery Check"
                )
            }
        }

        // Observe custom SAF download folder configuration
        appScope.launch {
            container.settingsRepository.settings
                .map { it.customDownloadTreeUri }
                .distinctUntilChanged()
                .collect { treeUri ->
                    container.storageManager.setCustomDownloadTreeUri(treeUri)
                }
        }

        // Observe mDNS discovery refresh interval
        appScope.launch {
            container.settingsRepository.settings
                .map { it.discoveryIntervalSeconds }
                .distinctUntilChanged()
                .collect { seconds ->
                    container.nsdDiscoveryManager.updateDiscoveryInterval(seconds)
                }
        }

        // Observe port setting and restart TCP server cleanly when port changes
        appScope.launch {
            container.settingsRepository.settings
                .map { it.port }
                .distinctUntilChanged()
                .collect { port ->
                    try {
                        val currentPort = container.tcpServer.listeningPort.value
                        val isRunning = container.tcpServer.isRunning.value
                        if (!isRunning || currentPort != port) {
                            if (isRunning) {
                                container.tcpServer.stop()
                            }
                            container.tcpServer.start(port)
                        }
                    } catch (e: Exception) {
                        AppErrorReporter.reportThrowable(
                            category = ErrorCategory.CONNECTION,
                            throwable = e,
                            targetLabel = "Local TCP Listener",
                            address = "Port $port"
                        )
                    }
                }
        }

        // Sync discovered mDNS endpoints to paired devices for automatic reconnection + widget refresh
        appScope.launch {
            combine(
                container.deviceDiscoveryRepository.discoveredDevices,
                container.pairedDeviceRepository.pairedDevices
            ) { discovered, paired ->
                discovered to paired
            }.collect { (discovered, paired) ->
                container.connectionManager.syncDiscoveredPeers(discovered, paired)
                WidgetUpdateHelper.requestUpdateAll(this@LocalDropApp)
            }
        }

        // Refresh widgets when scanning status changes
        appScope.launch {
            container.deviceDiscoveryRepository.isScanning.collect {
                WidgetUpdateHelper.requestUpdateAll(this@LocalDropApp)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        container.tcpServer.stop()
        container.connectionManager.cleanup()
        container.nsdDiscoveryManager.cleanup()
    }
}
