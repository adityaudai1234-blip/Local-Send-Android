package com.example.presentation.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    val pairingManager: PairingManager,
    val transferManager: TransferManager,
    val pairedDeviceRepository: PairedDeviceRepository,
    private val connectionManager: ConnectionManager? = null
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val connectionStatus: StateFlow<ConnectionStatus> = discoveryRepository.connectionStatus
    val discoveredDevices: StateFlow<List<Device>> = discoveryRepository.discoveredDevices
    val pairedDevices: StateFlow<List<PairedDevice>> = pairedDeviceRepository.pairedDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transferStats: StateFlow<TransferStats> = transferRepository.stats
    val isScanning: StateFlow<Boolean> = discoveryRepository.isScanning

    // Incoming file transfer request from peer
    val incomingRequest: StateFlow<IncomingTransferRequest?> = transferManager.incomingRequest

    private val _isReceivingActive = MutableStateFlow(true)
    val isReceivingActive: StateFlow<Boolean> = _isReceivingActive.asStateFlow()

    private val _showGenerateQr = MutableStateFlow(false)
    val showGenerateQr: StateFlow<Boolean> = _showGenerateQr.asStateFlow()

    // File selection, device dispatch & transfer confirmation workflow state
    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    private val _pendingTargets = MutableStateFlow<List<Device>>(emptyList())
    val pendingTargets: StateFlow<List<Device>> = _pendingTargets.asStateFlow()

    private val _showFileSelectionDialog = MutableStateFlow(false)
    val showFileSelectionDialog: StateFlow<Boolean> = _showFileSelectionDialog.asStateFlow()

    private val _showDeviceSelectionDialog = MutableStateFlow(false)
    val showDeviceSelectionDialog: StateFlow<Boolean> = _showDeviceSelectionDialog.asStateFlow()

    private val _showConfirmationDialog = MutableStateFlow(false)
    val showConfirmationDialog: StateFlow<Boolean> = _showConfirmationDialog.asStateFlow()

    private val _shareAccessWarning = MutableStateFlow<String?>(null)
    val shareAccessWarning: StateFlow<String?> = _shareAccessWarning.asStateFlow()

    /**
     * Quick Send rail: combines Favorites, Last Used destination, and Recently Used/Discovered peers.
     */
    val quickSendDevices: StateFlow<List<Device>> = combine(
        pairedDevices,
        discoveredDevices,
        settings,
        connectionManager?.connectionStates ?: MutableStateFlow(emptyMap())
    ) { paired, discovered, currentSettings, connStates ->
        val discoveredById = discovered.associateBy { it.id }
        val result = mutableListOf<Device>()
        val addedIds = mutableSetOf<String>()

        fun pairedToDevice(p: PairedDevice): Device {
            val live = discoveredById[p.id] ?: discovered.find { it.ipAddress == p.ipAddress }
            val connected = connStates[p.id] is ConnectionState.Connected || p.isConnected || live?.isOnline == true
            return Device(
                id = p.id,
                alias = p.alias,
                deviceType = p.deviceType,
                ipAddress = live?.ipAddress ?: p.ipAddress,
                port = live?.port ?: p.port,
                isOnline = connected
            )
        }

        // 1. Favorite paired devices always appear first
        paired.filter { it.isFavorite }.forEach { fav ->
            if (addedIds.add(fav.id)) result.add(pairedToDevice(fav))
        }

        if (currentSettings.suggestRecentDevices) {
            // 2. Remembered last transfer destination
            val lastId = currentSettings.lastDestinationDeviceId
            if (currentSettings.rememberLastDestination && !lastId.isNullOrBlank()) {
                val lastPaired = paired.find { it.id == lastId }
                if (lastPaired != null && addedIds.add(lastPaired.id)) {
                    result.add(pairedToDevice(lastPaired))
                } else {
                    discoveredById[lastId]?.let { d ->
                        if (addedIds.add(d.id)) result.add(d)
                    }
                }
            }

            // 3. Frequently / recently used paired devices
            paired.sortedWith(
                compareByDescending<PairedDevice> { it.lastTransferredAt }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastConnectedAt }
            ).forEach { p ->
                if (addedIds.add(p.id)) result.add(pairedToDevice(p))
            }

            // 4. Online nearby discovered devices
            discovered.forEach { d ->
                if (addedIds.add(d.id)) result.add(d)
            }
        }

        result.take(8)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            settings.collect { currentSettings ->
                if (currentSettings.broadcastEnabled) {
                    discoveryRepository.startAdvertising(currentSettings.deviceName, currentSettings.port)
                }
            }
        }
        discoveryRepository.startDiscovery()
    }

    fun clearShareAccessWarning() {
        _shareAccessWarning.value = null
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
        viewModelScope.launch {
            val newFiles = withContext(Dispatchers.IO) {
                uris.map { SelectedFile.fromUri(context, it) }
            }
            if (newFiles.isEmpty()) return@launch
            val brokenCount = newFiles.count { !it.isAccessible }
            if (brokenCount > 0) {
                _shareAccessWarning.value = "$brokenCount selected file(s) could not be read and may need re-selection."
            }
            _selectedFiles.update { it + newFiles }

            if (preselectedTarget != null) {
                _pendingTargets.value = listOf(preselectedTarget)
                _showFileSelectionDialog.value = false
                _showDeviceSelectionDialog.value = false
                _showConfirmationDialog.value = true
            } else {
                _showFileSelectionDialog.value = true
            }
        }
    }

    fun onFolderPicked(treeUri: Uri, context: Context, preselectedTarget: Device? = null) {
        viewModelScope.launch {
            val folderFiles = withContext(Dispatchers.IO) {
                SelectedFile.fromTreeUri(context, treeUri)
            }
            if (folderFiles.isEmpty()) return@launch
            _selectedFiles.update { it + folderFiles }

            if (preselectedTarget != null) {
                _pendingTargets.value = listOf(preselectedTarget)
                _showFileSelectionDialog.value = false
                _showDeviceSelectionDialog.value = false
                _showConfirmationDialog.value = true
            } else {
                _showFileSelectionDialog.value = true
            }
        }
    }

    fun onSharedFilesReceived(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val sharedFiles = withContext(Dispatchers.IO) {
                uris.map { SelectedFile.fromUri(context, it) }
            }
            if (sharedFiles.isEmpty()) return@launch
            val brokenCount = sharedFiles.count { !it.isAccessible }
            if (brokenCount > 0) {
                _shareAccessWarning.value = "$brokenCount shared file(s) could not be accessed due to expired provider permissions."
            }
            _selectedFiles.value = sharedFiles
            _showFileSelectionDialog.value = brokenCount > 0
            _showConfirmationDialog.value = false
            _showDeviceSelectionDialog.value = brokenCount == 0
        }
    }

    fun onSharedTextReceived(text: String, subject: String?, context: Context) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val noteFile = withContext(Dispatchers.IO) {
                SelectedFile.createTextNoteFile(context, text, subject)
            } ?: return@launch
            _selectedFiles.value = listOf(noteFile)
            _showFileSelectionDialog.value = false
            _showConfirmationDialog.value = false
            _showDeviceSelectionDialog.value = true
        }
    }

    fun resolveQuickSendTarget(deviceId: String?): Device? {
        if (deviceId.isNullOrBlank()) return quickSendDevices.value.firstOrNull()
        return quickSendDevices.value.find { it.id == deviceId }
            ?: discoveredDevices.value.find { it.id == deviceId }
    }

    fun estimateTransferSeconds(): Long? {
        val totalBytes = _selectedFiles.value.sumOf { it.size }
        return transferManager.estimateTransferSeconds(totalBytes)
    }

    fun addMoreFiles(newFiles: List<SelectedFile>) {
        _selectedFiles.update { it + newFiles }
    }

    fun removeSelectedFile(file: SelectedFile) {
        _selectedFiles.update { list -> list.filter { it.uri != file.uri || it.relativePath != file.relativePath } }
        if (_selectedFiles.value.isEmpty()) {
            _showFileSelectionDialog.value = false
            _showConfirmationDialog.value = false
        }
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
        _showFileSelectionDialog.value = false
        _showConfirmationDialog.value = false
    }

    fun proceedToDeviceSelection() {
        _showFileSelectionDialog.value = false
        _showConfirmationDialog.value = false
        _showDeviceSelectionDialog.value = true
    }

    fun proceedToConfirmation(targets: List<Device>) {
        if (targets.isEmpty() || _selectedFiles.value.isEmpty()) return
        _pendingTargets.value = targets
        _showDeviceSelectionDialog.value = false
        _showFileSelectionDialog.value = false
        _showConfirmationDialog.value = true
    }

    fun backToFileSelection() {
        _showDeviceSelectionDialog.value = false
        _showConfirmationDialog.value = false
        _showFileSelectionDialog.value = true
    }

    fun backFromConfirmationToDevices() {
        _showConfirmationDialog.value = false
        _showDeviceSelectionDialog.value = true
    }

    fun dismissFileFlow() {
        _selectedFiles.value = emptyList()
        _pendingTargets.value = emptyList()
        _showFileSelectionDialog.value = false
        _showDeviceSelectionDialog.value = false
        _showConfirmationDialog.value = false
    }

    fun confirmAndSendTransfers() {
        val filesToSend = _selectedFiles.value
        val targets = _pendingTargets.value
        if (filesToSend.isNotEmpty() && targets.isNotEmpty()) {
            transferManager.sendFiles(filesToSend, targets)
        }
        dismissFileFlow()
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
