package com.example.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.pairing.PairingManager
import com.example.data.pairing.PairingPayload
import com.example.data.pairing.PairingResult
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.model.PairedDevice
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.PairedDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val discoveryRepository: DeviceDiscoveryRepository,
    val pairedDeviceRepository: PairedDeviceRepository,
    val connectionManager: ConnectionManager,
    val pairingManager: PairingManager
) : ViewModel() {

    val devices: StateFlow<List<Device>> = discoveryRepository.discoveredDevices
    val isScanning: StateFlow<Boolean> = discoveryRepository.isScanning
    val connectionStatus: StateFlow<ConnectionStatus> = discoveryRepository.connectionStatus
    val error: StateFlow<String?> = discoveryRepository.error

    val pairedDevices: StateFlow<List<PairedDevice>> = pairedDeviceRepository.pairedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionStates: StateFlow<Map<String, ConnectionState>> = connectionManager.connectionStates
    val pairingResult: StateFlow<PairingResult> = pairingManager.pairingResult

    private val _showGenerateQr = MutableStateFlow(false)
    val showGenerateQr: StateFlow<Boolean> = _showGenerateQr.asStateFlow()

    private val _showScanQr = MutableStateFlow(false)
    val showScanQr: StateFlow<Boolean> = _showScanQr.asStateFlow()

    private val _pendingScannedPayload = MutableStateFlow<PairingPayload?>(null)
    val pendingScannedPayload: StateFlow<PairingPayload?> = _pendingScannedPayload.asStateFlow()

    private val _selectedDeviceForDetails = MutableStateFlow<PairedDevice?>(null)
    val selectedDeviceForDetails: StateFlow<PairedDevice?> = _selectedDeviceForDetails.asStateFlow()

    init {
        discoveryRepository.startDiscovery()
    }

    fun refreshDiscovery() {
        discoveryRepository.refreshDiscovery()
    }

    fun openGenerateQr() {
        _showGenerateQr.value = true
    }

    fun closeGenerateQr() {
        _showGenerateQr.value = false
    }

    fun openScanQr() {
        _showScanQr.value = true
    }

    fun closeScanQr() {
        _showScanQr.value = false
    }

    fun onPayloadScanned(payload: PairingPayload) {
        _showScanQr.value = false
        _pendingScannedPayload.value = payload
    }

    fun dismissConfirmation() {
        _pendingScannedPayload.value = null
    }

    fun confirmPairing(payload: PairingPayload) {
        _pendingScannedPayload.value = null
        viewModelScope.launch {
            pairingManager.pairWithScannedDevice(payload)
        }
    }

    fun connectDevice(device: PairedDevice) {
        viewModelScope.launch {
            val token = pairedDeviceRepository.getAuthToken(device.id)
            connectionManager.connect(device, token)
        }
    }

    fun disconnectDevice(deviceId: String) {
        connectionManager.disconnect(deviceId)
    }

    fun openDeviceDetails(device: PairedDevice) {
        _selectedDeviceForDetails.value = device
    }

    fun closeDeviceDetails() {
        _selectedDeviceForDetails.value = null
    }

    fun renamePairedDevice(deviceId: String, newAlias: String) {
        viewModelScope.launch {
            pairedDeviceRepository.renameDevice(deviceId, newAlias)
        }
    }

    fun removePairedDevice(deviceId: String) {
        viewModelScope.launch {
            connectionManager.disconnect(deviceId)
            pairedDeviceRepository.removePairedDevice(deviceId)
        }
    }

    fun resetPairingResult() {
        pairingManager.resetPairingResult()
    }
}
