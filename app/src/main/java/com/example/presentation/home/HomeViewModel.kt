package com.example.presentation.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionStatus
import com.example.domain.model.Device
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class HomeViewModel(
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val connectionStatus: StateFlow<ConnectionStatus> = discoveryRepository.connectionStatus
    val discoveredDevices: StateFlow<List<Device>> = discoveryRepository.discoveredDevices
    val transferStats: StateFlow<TransferStats> = transferRepository.stats
    val isScanning: StateFlow<Boolean> = discoveryRepository.isScanning

    private val _isReceivingActive = MutableStateFlow(true)
    val isReceivingActive: StateFlow<Boolean> = _isReceivingActive.asStateFlow()

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

    fun handleSelectedFiles(uris: List<Uri>, context: Context, targetDevice: Device? = null) {
        viewModelScope.launch {
            for (uri in uris) {
                var fileName = "file_${System.currentTimeMillis()}"
                var fileSize = 1_024_000L

                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex).coerceAtLeast(1024L)
                        }
                    }
                } catch (_: Exception) {
                    // Fallback to defaults
                }

                val peerName = targetDevice?.alias ?: discoveredDevices.value.firstOrNull()?.alias ?: "Nearby Device"
                val peerAddress = targetDevice?.displaySubtitle ?: "mDNS Peer"

                val item = TransferItem(
                    id = UUID.randomUUID().toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    direction = TransferDirection.SEND,
                    status = TransferStatus.ACTIVE,
                    progress = 0.05f,
                    speed = "Connecting…",
                    peerDeviceName = peerName,
                    peerAddress = peerAddress,
                    timestamp = System.currentTimeMillis()
                )
                transferRepository.addTransfer(item)
            }
        }
    }

    fun addTestPeer() {
        discoveryRepository.addPredefinedTestPeers()
    }
}
