package com.example.presentation.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransferDao
import com.example.data.transfer.StorageManager
import com.example.domain.model.AppSettings
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageUiState(
    val files: List<File> = emptyList(),
    val receivedCount: Int = 0,
    val receivedBytes: Long = 0L,
    val tempBytes: Long = 0L,
    val usableBytes: Long = 0L,
    val downloadDirectoryPath: String = "",
    val isLoading: Boolean = true
)

class StorageViewModel(
    private val storageManager: StorageManager,
    private val settingsRepository: SettingsRepository,
    private val transferDao: TransferDao
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val files = storageManager.listReceivedFiles()
                StorageUiState(
                    files = files,
                    receivedCount = files.size,
                    receivedBytes = files.sumOf { it.length() },
                    tempBytes = storageManager.getTotalTempStorageBytes(),
                    usableBytes = storageManager.getUsableStorageBytes(),
                    downloadDirectoryPath = storageManager.getDownloadDirectory().absolutePath,
                    isLoading = false
                )
            }
            _uiState.value = state
        }
    }

    fun setCustomDownloadFolder(displayPath: String, treeUri: String?) {
        viewModelScope.launch {
            settingsRepository.setDownloadLocation(displayPath, treeUri)
            refreshStorage()
        }
    }

    fun clearTempFiles(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { storageManager.cleanAllTempFiles() }
            refreshStorage()
            onComplete(deleted)
        }
    }

    fun deleteSelectedFiles(paths: Set<String>, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                paths.forEach { path ->
                    val file = File(path)
                    if (file.exists() && storageManager.deleteFile(file)) {
                        count++
                    }
                }
                count
            }
            refreshStorage()
            onComplete(deletedCount)
        }
    }

    fun deleteAllReceivedFiles(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { storageManager.deleteAllReceivedFiles() }
            refreshStorage()
            onComplete(deleted)
        }
    }

    fun openFile(file: File) {
        storageManager.openFile(file)
    }

    fun shareFile(file: File) {
        storageManager.shareFile(file)
    }
}
