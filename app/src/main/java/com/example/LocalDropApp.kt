package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LocalDropApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)

        // Startup recovery: remove orphaned .tmp files left from unexpected termination
        appScope.launch {
            container.storageManager.cleanAllTempFiles()
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
    }

    override fun onTerminate() {
        super.onTerminate()
        container.tcpServer.stop()
        container.connectionManager.cleanup()
        container.nsdDiscoveryManager.cleanup()
    }
}
