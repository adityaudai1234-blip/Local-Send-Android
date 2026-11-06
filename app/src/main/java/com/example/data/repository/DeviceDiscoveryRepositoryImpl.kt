package com.example.data.repository

import android.content.Context
import com.example.data.discovery.NsdDiscoveryManager
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.model.DeviceType
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class DeviceDiscoveryRepositoryImpl(
    private val context: Context,
    private val nsdManager: NsdDiscoveryManager
) : DeviceDiscoveryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(NetworkUtils.getConnectionStatus(context))
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    override val discoveredDevices: StateFlow<List<Device>> = nsdManager.discoveredDevices
    override val isScanning: StateFlow<Boolean> = nsdManager.isScanning
    override val isAdvertising: StateFlow<Boolean> = nsdManager.isAdvertising
    override val error: StateFlow<String?> = nsdManager.error

    init {
        updateNetworkStatus()
    }

    override fun startDiscovery() {
        updateNetworkStatus()
        nsdManager.startDiscovery()
    }

    override fun stopDiscovery() {
        nsdManager.stopDiscovery()
    }

    override fun refreshDiscovery() {
        updateNetworkStatus()
        nsdManager.refreshDiscovery()
    }

    override fun startAdvertising(alias: String, port: Int) {
        updateNetworkStatus()
        nsdManager.startAdvertising(alias, port)
    }

    override fun stopAdvertising() {
        nsdManager.stopAdvertising()
    }

    override fun updateNetworkStatus() {
        scope.launch {
            _connectionStatus.value = NetworkUtils.getConnectionStatus(context)
        }
    }

    override fun addSimulatedPeer(device: Device) {
        nsdManager.addSimulatedPeer(device)
    }

    override fun addPredefinedTestPeers() {
        val testPeers = listOf(
            Device(
                id = "tauri-desktop-win-${UUID.randomUUID().toString().take(4)}",
                alias = "Tauri Studio PC",
                deviceType = DeviceType.WINDOWS,
                ipAddress = "192.168.1.142",
                port = 53317,
                model = "Windows 11 (Tauri 2.0)",
                protocolVersion = "v1"
            ),
            Device(
                id = "tauri-macbook-${UUID.randomUUID().toString().take(4)}",
                alias = "MacBook Pro M3",
                deviceType = DeviceType.MACOS,
                ipAddress = "192.168.1.189",
                port = 53317,
                model = "macOS Sonoma (Tauri)",
                protocolVersion = "v1"
            ),
            Device(
                id = "linux-workstation-${UUID.randomUUID().toString().take(4)}",
                alias = "Ubuntu Devbox",
                deviceType = DeviceType.LINUX,
                ipAddress = "192.168.1.105",
                port = 53317,
                model = "Ubuntu 24.04 LTS",
                protocolVersion = "v1"
            )
        )
        testPeers.forEach { nsdManager.addSimulatedPeer(it) }
    }
}
