package com.example.presentation.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

class TransfersViewModel(
    private val transferRepository: TransferRepository,
    val transferManager: TransferManager,
    val storageManager: StorageManager
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow(TransferCategory.ACTIVE)
    val selectedCategory: StateFlow<TransferCategory> = _selectedCategory.asStateFlow()

    val stats: StateFlow<TransferStats> = transferRepository.stats

    val currentTransfers = combine(
        transferRepository.transfers,
        _selectedCategory
    ) { list, category ->
        list.filter { it.category == category }
    }

    private val _selectedDetailItem = MutableStateFlow<TransferItem?>(null)
    val selectedDetailItem: StateFlow<TransferItem?> = _selectedDetailItem.asStateFlow()

    fun selectCategory(category: TransferCategory) {
        _selectedCategory.value = category
    }

    fun openTransferDetails(item: TransferItem) {
        _selectedDetailItem.value = item
    }

    fun closeTransferDetails() {
        _selectedDetailItem.value = null
    }

    fun pauseTransfer(id: String) {
        transferManager.pauseTransfer(id)
    }

    fun resumeTransfer(id: String) {
        transferManager.resumeTransfer(id)
    }

    fun cancelTransfer(id: String) {
        transferRepository.cancelTransfer(id)
    }

    fun retryTransfer(id: String) {
        transferRepository.retryTransfer(id)
    }

    fun clearCompleted() {
        transferRepository.clearCompleted()
    }

    fun openFile(item: TransferItem) {
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            val file = java.io.File(path)
            if (file.exists()) {
                storageManager.openFile(file)
            }
        }
    }

    fun shareFile(item: TransferItem) {
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            val file = java.io.File(path)
            if (file.exists()) {
                storageManager.shareFile(file)
            }
        }
    }

    fun resetMockData() {
        transferRepository.populateSampleTransfers()
    }
}
