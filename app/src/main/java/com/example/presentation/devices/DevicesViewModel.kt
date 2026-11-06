package com.example.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.repository.DeviceDiscoveryRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val discoveryRepository: DeviceDiscoveryRepository
) : ViewModel() {

    val devices: StateFlow<List<Device>> = discoveryRepository.discoveredDevices
    val isScanning: StateFlow<Boolean> = discoveryRepository.isScanning
    val connectionStatus: StateFlow<ConnectionStatus> = discoveryRepository.connectionStatus
    val error: StateFlow<String?> = discoveryRepository.error

    init {
        discoveryRepository.startDiscovery()
    }

    fun refreshDiscovery() {
        discoveryRepository.refreshDiscovery()
    }

    fun addTestPeers() {
        discoveryRepository.addPredefinedTestPeers()
    }
}
