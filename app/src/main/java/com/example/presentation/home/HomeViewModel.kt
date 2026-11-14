package com.example.presentation.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.pairing.PairingManager
import com.example.data.transfer.IncomingTransferRequest
import com.example.data.transfer.TransferManager
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.model.PairedDevice
import com.example.domain.model.TransferStats
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.TransferRepository
import com.example.presentation.transfer.SelectedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    val pairingManager: PairingManager,
    val transferManager: TransferManager,
    val pairedDeviceRepository: PairedDeviceRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val connectionStatus: StateFlow<ConnectionStatus> = discoveryRepository.connectionStatus
    val discoveredDevices: StateFlow<List<Device>> = discoveryRepository.discoveredDevices
    val pairedDevices: StateFlow<List<PairedDevice>> = pairedDeviceRepository.pairedDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<PairedDevice>())
    val transferStats: StateFlow<TransferStats> = transferRepository.stats
    val isScanning: StateFlow<Boolean> = discoveryRepository.isScanning

    // Incoming file transfer request from peer
    val incomingRequest: StateFlow<IncomingTransferRequest?> = transferManager.incomingRequest

    private val _isReceivingActive = MutableStateFlow(true)
    val isReceivingActive: StateFlow<Boolean> = _isReceivingActive.asStateFlow()

    private val _showGenerateQr = MutableStateFlow(false)
    val showGenerateQr: StateFlow<Boolean> = _showGenerateQr.asStateFlow()

    // File selection & device dispatch workflow state
    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    private val _showFileSelectionDialog = MutableStateFlow(false)
    val showFileSelectionDialog: StateFlow<Boolean> = _showFileSelectionDialog.asStateFlow()

    private val _showDeviceSelectionDialog = MutableStateFlow(false)
    val showDeviceSelectionDialog: StateFlow<Boolean> = _showDeviceSelectionDialog.asStateFlow()

    init {
        // Start advertising our device and discover nearby peers
        viewModelScope.launch {
            settings.collect { currentSettings ->
                if (currentSettings.broadcastEnabled) {
                    discoveryRepository.startAdvertising(currentSettings.deviceName, currentSettings.port)
                }
            }
        }
        discoveryRepository.startDiscovery()
    }

    fun openGenerateQr() {
        _showGenerateQr.value = true
    }

    fun closeGenerateQr() {
        _showGenerateQr.value = false
    }

    fun toggleReceiving() {
        val nextState = !_isReceivingActive.value
        _isReceivingActive.value = nextState
        if (nextState) {
            discoveryRepository.startAdvertising(settings.value.deviceName, settings.value.port)
        } else {
            discoveryRepository.stopAdvertising()
        }
    }

    fun refreshDiscovery() {
        discoveryRepository.refreshDiscovery()
    }

    fun onFilesPicked(uris: List<Uri>, context: Context, preselectedTarget: Device? = null) {
        val newFiles = uris.map { SelectedFile.fromUri(context, it) }
        _selectedFiles.update { it + newFiles }

        if (preselectedTarget != null) {
            // Target is already chosen, send directly
            transferManager.sendFiles(_selectedFiles.value, listOf(preselectedTarget))
            _selectedFiles.value = emptyList()
            _showFileSelectionDialog.value = false
            _showDeviceSelectionDialog.value = false
        } else {
            _showFileSelectionDialog.value = true
        }
    }

    fun addMoreFiles(newFiles: List<SelectedFile>) {
        _selectedFiles.update { it + newFiles }
    }

    fun removeSelectedFile(file: SelectedFile) {
        _selectedFiles.update { list -> list.filter { it.uri != file.uri } }
        if (_selectedFiles.value.isEmpty()) {
            _showFileSelectionDialog.value = false
        }
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
        _showFileSelectionDialog.value = false
    }

    fun proceedToDeviceSelection() {
        _showFileSelectionDialog.value = false
        _showDeviceSelectionDialog.value = true
    }

    fun backToFileSelection() {
        _showDeviceSelectionDialog.value = false
        _showFileSelectionDialog.value = true
    }

    fun dismissFileFlow() {
        _selectedFiles.value = emptyList()
        _showFileSelectionDialog.value = false
        _showDeviceSelectionDialog.value = false
    }

    fun sendSelectedFilesToTargets(targets: List<Device>) {
        val filesToSend = _selectedFiles.value
        if (filesToSend.isNotEmpty() && targets.isNotEmpty()) {
            transferManager.sendFiles(filesToSend, targets)
        }
        dismissFileFlow()
    }

    fun acceptIncomingTransfer(request: IncomingTransferRequest) {
        transferManager.acceptIncomingTransfer(request)
    }

    fun rejectIncomingTransfer(request: IncomingTransferRequest) {
        transferManager.rejectIncomingTransfer(request)
    }

    fun handleSelectedFiles(uris: List<Uri>, context: Context, targetDevice: Device? = null) {
        onFilesPicked(uris, context, targetDevice)
    }
}
