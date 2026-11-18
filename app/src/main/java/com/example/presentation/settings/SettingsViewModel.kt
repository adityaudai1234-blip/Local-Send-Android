package com.example.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.transfer.StorageManager
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsStorageInfo(
    val receivedBytes: Long = 0L,
    val tempBytes: Long = 0L,
    val usableBytes: Long = 0L,
    val receivedCount: Int = 0
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val storageManager: StorageManager,
    private val nsdDiscoveryManager: NsdDiscoveryManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val pairedDeviceCount: StateFlow<Int> = pairedDeviceRepository.pairedDevices
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _storageInfo = MutableStateFlow(SettingsStorageInfo())
    val storageInfo: StateFlow<SettingsStorageInfo> = _storageInfo.asStateFlow()

    private val _networkSummary = MutableStateFlow(NetworkUtils.getActiveInterfaceSummary())
    val networkSummary: StateFlow<String> = _networkSummary.asStateFlow()

    init {
        refreshDiagnostics()
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                val files = storageManager.listReceivedFiles()
                SettingsStorageInfo(
                    receivedBytes = files.sumOf { it.length() },
                    tempBytes = storageManager.getTotalTempStorageBytes(),
                    usableBytes = storageManager.getUsableStorageBytes(),
                    receivedCount = files.size
                )
            }
            _storageInfo.value = info
            _networkSummary.value = withContext(Dispatchers.IO) {
                NetworkUtils.getActiveInterfaceSummary()
            }
        }
    }

    fun updateDeviceName(name: String) = viewModelScope.launch { settingsRepository.setDeviceName(name) }
    fun updateThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun updateAccentColor(option: AccentColorOption) = viewModelScope.launch { settingsRepository.setAccentColor(option) }
    fun updateAutoStartReceiving(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoStartReceiving(enabled) }
    fun updateAutoAcceptTrusted(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoAcceptTrusted(enabled) }
    fun updateAskBeforeReceiving(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAskBeforeReceiving(enabled) }
    fun updateDownloadLocation(path: String, treeUri: String? = null) = viewModelScope.launch {
        settingsRepository.setDownloadLocation(path, treeUri)
    }
    fun updatePort(port: Int) = viewModelScope.launch { settingsRepository.setPort(port) }
    fun updateBroadcastEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBroadcastEnabled(enabled)
        if (!enabled) {
            nsdDiscoveryManager.stopAdvertising()
        } else {
            nsdDiscoveryManager.startAdvertising(settings.value.deviceName, settings.value.port)
        }
    }
    fun updateShowNotifications(enabled: Boolean) = viewModelScope.launch { settingsRepository.setShowNotifications(enabled) }
    fun updateDiscoveryIntervalSeconds(seconds: Int) = viewModelScope.launch {
        settingsRepository.setDiscoveryIntervalSeconds(seconds)
        nsdDiscoveryManager.updateDiscoveryInterval(seconds)
    }
    fun updateConnectionTimeoutMs(timeoutMs: Int) = viewModelScope.launch { settingsRepository.setConnectionTimeoutMs(timeoutMs) }
    fun updateMaxConcurrent(count: Int) = viewModelScope.launch { settingsRepository.setMaxConcurrent(count) }
    fun updateDuplicateHandling(handling: String) = viewModelScope.launch { settingsRepository.setDuplicateHandling(handling) }
    fun updatePreserveOriginalNames(enabled: Boolean) = viewModelScope.launch { settingsRepository.setPreserveOriginalNames(enabled) }
    fun updateAutoOpenCompleted(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoOpenCompleted(enabled) }
    fun updateMaxAcceptedFileSizeMb(sizeMb: Long) = viewModelScope.launch { settingsRepository.setMaxAcceptedFileSizeMb(sizeMb) }
    fun updateRequireApprovalUnknown(enabled: Boolean) = viewModelScope.launch { settingsRepository.setRequireApprovalUnknown(enabled) }
    fun updateTlsPreferred(enabled: Boolean) = viewModelScope.launch { settingsRepository.setTlsPreferred(enabled) }
    fun updateHapticsEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setHapticsEnabled(enabled) }
    fun updateTransferBufferSizeKb(sizeKb: Int) = viewModelScope.launch { settingsRepository.setTransferBufferSizeKb(sizeKb) }
    fun updateSuggestRecentDevices(enabled: Boolean) = viewModelScope.launch { settingsRepository.setSuggestRecentDevices(enabled) }
    fun updateRememberLastDestination(enabled: Boolean) = viewModelScope.launch { settingsRepository.setRememberLastDestination(enabled) }
    fun updateOrganizeReceivedByType(enabled: Boolean) = viewModelScope.launch { settingsRepository.setOrganizeReceivedByType(enabled) }
    fun updateAutoCleanTempFiles(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAutoCleanTempFiles(enabled) }

    fun clearTempFiles(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { storageManager.cleanAllTempFiles() }
            refreshDiagnostics()
            onResult(count)
        }
    }

    fun clearStoredCredentials(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { pairedDeviceRepository.clearAllDevices() }
            onDone()
        }
    }
}
