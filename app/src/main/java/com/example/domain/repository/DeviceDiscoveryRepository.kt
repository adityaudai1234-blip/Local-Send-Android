package com.example.domain.repository

import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import kotlinx.coroutines.flow.StateFlow

interface DeviceDiscoveryRepository {
    val discoveredDevices: StateFlow<List<Device>>
    val isScanning: StateFlow<Boolean>
    val isAdvertising: StateFlow<Boolean>
    val connectionStatus: StateFlow<ConnectionStatus>
    val error: StateFlow<String?>

    fun startDiscovery()
    fun stopDiscovery()
    fun refreshDiscovery()
    fun startAdvertising(alias: String, port: Int = 53317)
    fun stopAdvertising()
    fun updateNetworkStatus()
    fun addSimulatedPeer(device: Device)
    fun addPredefinedTestPeers()
}
