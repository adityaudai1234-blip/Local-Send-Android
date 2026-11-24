package com.example.presentation.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.network.ConnectionErrorEvent
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.network.NetworkServer
import com.example.domain.model.AppError
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.NetworkTransportType
import com.example.domain.model.PairedDevice
import com.example.domain.model.TransferItem
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.util.LocalCrashReporter
import com.example.util.NetworkUtils
import com.example.util.PerformanceMonitor
import com.example.util.PerformanceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class DiagnosticsReport(
    val localIp: String = "Unavailable",
    val localIpv6: String = "Unavailable",
    val transportTypeLabel: String = "Offline",
    val interfaceName: String = "Unavailable",
    val isHotspot: Boolean = false,
    val interfaceSummary: String = "Unavailable",
    val networkName: String = "Offline",
    val isNetworkConnected: Boolean = false,
    val isServerRunning: Boolean = false,
    val listeningPort: Int = 53317,
    val mdnsPort: Int = 5353,
    val activeTransferSocketCount: Int = 0,
    val discoveryStatusText: String = "Idle",
    val discoveredCount: Int = 0,
    val connectedPairedCount: Int = 0,
    val isNetworkMonitorActive: Boolean = false,
    val localPortCheckMessage: String? = null,
    val peerLatencyResults: Map<String, String> = emptyMap(),
    val isTesting: Boolean = false
)

class DiagnosticsViewModel(
    private val context: Context,
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val connectionManager: ConnectionManager,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val tcpServer: NetworkServer,
    private val settingsRepository: SettingsRepository,
    private val performanceMonitor: PerformanceMonitor = PerformanceMonitor()
) : ViewModel() {

    val recentErrors: StateFlow<List<ConnectionErrorEvent>> = connectionManager.recentErrors
    val appErrors: StateFlow<List<AppError>> = AppErrorReporter.errors
    val pairedDevices: StateFlow<List<PairedDevice>> = pairedDeviceRepository.pairedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = connectionManager.connectionStates
    val peerLatenciesMs: StateFlow<Map<String, Long>> = connectionManager.peerLatenciesMs
    val performanceSnapshot: StateFlow<PerformanceSnapshot> = performanceMonitor.snapshot

    private val _report = MutableStateFlow(DiagnosticsReport())
    val report: StateFlow<DiagnosticsReport> = _report.asStateFlow()

    private val _lastCrashSummary = MutableStateFlow<String?>(null)
    val lastCrashSummary: StateFlow<String?> = _lastCrashSummary.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val status: ConnectionStatus = withContext(Dispatchers.IO) {
                NetworkUtils.getConnectionStatus(context)
            }
            val localIp = withContext(Dispatchers.IO) {
                NetworkUtils.getLocalIpv4Address() ?: "Not assigned"
            }
            val localIpv6 = withContext(Dispatchers.IO) {
                status.ipv6Address ?: NetworkUtils.getLocalIpv6Address() ?: "Unavailable"
            }
            val ifaceSummary = withContext(Dispatchers.IO) {
                NetworkUtils.getActiveInterfaceSummary()
            }
            performanceMonitor.refreshMemoryUsage()

            val discError = discoveryRepository.error.value
            val isScanning = discoveryRepository.isScanning.value
            val isAdvertising = discoveryRepository.isAdvertising.value
            val discText = when {
                !discError.isNullOrBlank() -> "Error: $discError"
                isScanning && isAdvertising -> "Scanning & Advertising (_lsend._tcp)"
                isScanning -> "Scanning (_lsend._tcp)"
                isAdvertising -> "Advertising (_lsend._tcp)"
                else -> "Idle"
            }
            val connectedCount = connectionManager.connectionStates.value.values.count {
                it is ConnectionState.Connected
            }
            val transportLabel = when {
                status.isHotspot -> "Hotspot / Tethering"
                status.transportType == NetworkTransportType.ETHERNET -> "Ethernet LAN"
                status.transportType == NetworkTransportType.WIFI -> "Wi-Fi"
                status.transportType == NetworkTransportType.CELLULAR -> "Cellular"
                status.isConnected -> "Local Network"
                else -> "Offline"
            }

            _report.value = _report.value.copy(
                localIp = localIp,
                localIpv6 = localIpv6,
                transportTypeLabel = transportLabel,
                interfaceName = status.interfaceName ?: "Unavailable",
                isHotspot = status.isHotspot,
                interfaceSummary = ifaceSummary,
                networkName = status.networkName,
                isNetworkConnected = status.isConnected,
                isServerRunning = tcpServer.isRunning.value,
                listeningPort = tcpServer.listeningPort.value,
                mdnsPort = 5353,
                activeTransferSocketCount = performanceMonitor.snapshot.value.activeSocketCount,
                discoveryStatusText = discText,
                discoveredCount = discoveryRepository.discoveredDevices.value.size,
                connectedPairedCount = connectedCount,
                isNetworkMonitorActive = connectionManager.isNetworkMonitorActive.value
            )
            _lastCrashSummary.value = withContext(Dispatchers.IO) {
                LocalCrashReporter.readLastCrashSummary(context)
            }
        }
    }

    fun runLocalNetworkAndPortTest() {
        viewModelScope.launch {
            _report.value = _report.value.copy(isTesting = true)
            val resultMsg = withContext(Dispatchers.IO) {
                val configuredPort = settingsRepository.settings.value.port
                val serverUp = tcpServer.isRunning.value && tcpServer.listeningPort.value == configuredPort
                if (serverUp) {
                    try {
                        Socket().use { sock ->
                            sock.connect(InetSocketAddress("127.0.0.1", configuredPort), 1500)
                        }
                        "TCP listener port $configuredPort is open and reachable locally."
                    } catch (e: Exception) {
                        "TCP listener failed loopback connect on port $configuredPort: ${e.message}"
                    }
                } else {
                    try {
                        ServerSocket(configuredPort).use {
                            "Port $configuredPort is available (TCP listener is currently stopped)."
                        }
                    } catch (e: Exception) {
                        "Port $configuredPort is unavailable or blocked: ${e.message}"
                    }
                }
            }
            _report.value = _report.value.copy(
                isTesting = false,
                localPortCheckMessage = resultMsg
            )
            refreshStatus()
        }
    }

    fun pingPairedDevice(device: PairedDevice) {
        viewModelScope.launch {
            _report.value = _report.value.copy(
                peerLatencyResults = _report.value.peerLatencyResults + (device.id to "Testing…")
            )
            val result = withContext(Dispatchers.IO) {
                connectionManager.testPeerLatencyMs(device)
            }
            val label = result.fold(
                onSuccess = { ms -> "Reachable (${ms}ms RTT)" },
                onFailure = { err -> "Failed (${err.message ?: "Timeout"})" }
            )
            _report.value = _report.value.copy(
                peerLatencyResults = _report.value.peerLatencyResults + (device.id to label)
            )
        }
    }

    fun restartDiscovery() {
        val currentSettings = settingsRepository.settings.value
        discoveryRepository.stopAdvertising()
        discoveryRepository.refreshDiscovery()
        if (currentSettings.broadcastEnabled) {
            discoveryRepository.startAdvertising(currentSettings.deviceName, currentSettings.port)
        }
        refreshStatus()
    }

    fun triggerReconnectAll() {
        connectionManager.triggerBoundedReconnect()
        refreshStatus()
    }

    fun clearErrors() {
        connectionManager.clearRecentErrors()
        AppErrorReporter.clear()
    }

    fun clearLastCrashSummary() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LocalCrashReporter.clearLastCrashSummary(context)
            }
            _lastCrashSummary.value = null
        }
    }

    fun buildCopyableNetworkReport(): String {
        val r = _report.value
        val perf = performanceSnapshot.value
        val sendRate = if (perf.measuredSendBytesPerSec > 0L) {
            "${TransferItem.formatBytes(perf.measuredSendBytesPerSec)}/s"
        } else "Not measured yet"
        val recvRate = if (perf.measuredReceiveBytesPerSec > 0L) {
            "${TransferItem.formatBytes(perf.measuredReceiveBytesPerSec)}/s"
        } else "Not measured yet"
        val peakRate = if (perf.peakMeasuredSpeedBytesPerSec > 0L) {
            "${TransferItem.formatBytes(perf.peakMeasuredSpeedBytesPerSec)}/s"
        } else "Not measured yet"

        return buildString {
            appendLine("=== LocalDrop Network Diagnostics ===")
            appendLine("Device: ${settingsRepository.settings.value.deviceName}")
            appendLine("Network: ${r.networkName} (${r.transportTypeLabel})")
            appendLine("Interface: ${r.interfaceName}")
            appendLine("Local IPv4: ${r.localIp}")
            appendLine("Local IPv6: ${r.localIpv6}")
            appendLine("Active Interfaces: ${r.interfaceSummary}")
            appendLine("mDNS Discovery (UDP ${r.mdnsPort}): ${r.discoveryStatusText}")
            appendLine("TCP Listener (Port ${r.listeningPort}): ${if (r.isServerRunning) "Listening" else "Stopped"}")
            appendLine("Discovered Nearby Peers: ${r.discoveredCount}")
            appendLine("Connected Paired Peers: ${r.connectedPairedCount}")
            appendLine("Active Transfer Sockets: ${perf.activeSocketCount}")
            appendLine("Network Monitor Active: ${r.isNetworkMonitorActive}")
            appendLine("Discovery Latency: ${if (perf.discoveryDurationMs > 0L) "${perf.discoveryDurationMs} ms" else "Not measured yet"}")
            appendLine("Last Peer Connect Time: ${if (perf.lastConnectionDurationMs > 0L) "${perf.lastConnectionDurationMs} ms" else "Not measured yet"}")
            appendLine("Measured Send Speed: $sendRate")
            appendLine("Measured Receive Speed: $recvRate")
            appendLine("Peak Measured Speed: $peakRate")
            appendLine("Retries: ${perf.totalRetries} | Recoveries: ${perf.recoverySuccesses}/${perf.recoveryAttempts}")
            if (perf.maxMemoryBytes > 0L) {
                appendLine("JVM Heap Used: ${TransferItem.formatBytes(perf.usedMemoryBytes)} / ${TransferItem.formatBytes(perf.maxMemoryBytes)}")
            }
            if (peerLatenciesMs.value.isNotEmpty()) {
                appendLine("Measured Peer RTTs:")
                peerLatenciesMs.value.forEach { (peerId, ms) ->
                    appendLine("  - $peerId: ${ms}ms")
                }
            }
        }.trim()
    }
}
