package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
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
        container = DefaultAppContainer(this)

        // Startup recovery & optional temp file maintenance
        appScope.launch {
            val currentSettings = container.settingsRepository.settings.first()
            if (currentSettings.autoCleanTempFiles) {
                container.storageManager.cleanAllTempFiles()
            } else {
                container.storageManager.cleanStaleTempFiles()
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
                    val currentPort = container.tcpServer.listeningPort.value
                    val isRunning = container.tcpServer.isRunning.value
                    if (!isRunning || currentPort != port) {
                        if (isRunning) {
                            container.tcpServer.stop()
                        }
                        container.tcpServer.start(port)
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
