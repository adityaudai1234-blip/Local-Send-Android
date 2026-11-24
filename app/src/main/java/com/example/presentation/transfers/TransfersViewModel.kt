package com.example.presentation.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class TransfersTab(val label: String) {
    ACTIVE("Active"),
    HISTORY("History")
}

enum class HistoryFilter(val label: String) {
    ALL("All"),
    SENT("Sent"),
    RECEIVED("Received"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

enum class HistorySort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    SMALLEST("Smallest first")
}

data class HistorySummary(
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val successfulCount: Int = 0,
    val failedCount: Int = 0
) {
    val formattedTotalData: String
        get() = TransferItem.formatBytes(totalBytes)
}

class TransfersViewModel(
    private val transferRepository: TransferRepository,
    val transferManager: TransferManager,
    val storageManager: StorageManager
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(TransfersTab.ACTIVE)
    val selectedTab: StateFlow<TransfersTab> = _selectedTab.asStateFlow()

    private val _selectedCategory = MutableStateFlow(TransferCategory.ACTIVE)
    val selectedCategory: StateFlow<TransferCategory> = _selectedCategory.asStateFlow()

    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter: StateFlow<HistoryFilter> = _historyFilter.asStateFlow()

    private val _historySort = MutableStateFlow(HistorySort.NEWEST)
    val historySort: StateFlow<HistorySort> = _historySort.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val stats: StateFlow<TransferStats> = transferRepository.stats
    val allTransfers: StateFlow<List<TransferItem>> = transferRepository.transfers

    val currentTransfers: StateFlow<List<TransferItem>> = combine(
        transferRepository.transfers,
        _selectedCategory
    ) { list, category ->
        list.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHistory: StateFlow<List<TransferItem>> = combine(
        transferRepository.transferHistory,
        _historyFilter,
        _historySort,
        _searchQuery
    ) { historyList, filter, sort, query ->
        val filtered = historyList.filter { item ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SENT -> item.direction == TransferDirection.SEND
                HistoryFilter.RECEIVED -> item.direction == TransferDirection.RECEIVE
                HistoryFilter.COMPLETED -> item.status == TransferStatus.COMPLETED
                HistoryFilter.FAILED -> item.status == TransferStatus.FAILED
                HistoryFilter.CANCELLED -> item.status == TransferStatus.CANCELLED
            }
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                item.fileName.contains(q, ignoreCase = true) ||
                item.peerDeviceName.contains(q, ignoreCase = true) ||
                item.senderDevice.contains(q, ignoreCase = true) ||
                item.receiverDevice.contains(q, ignoreCase = true) ||
                item.fileExtension.contains(q, ignoreCase = true)

            matchesFilter && matchesQuery
        }

        when (sort) {
            HistorySort.NEWEST -> filtered.sortedByDescending { it.timestamp }
            HistorySort.OLDEST -> filtered.sortedBy { it.timestamp }
            HistorySort.LARGEST -> filtered.sortedByDescending { it.fileSize }
            HistorySort.SMALLEST -> filtered.sortedBy { it.fileSize }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historySummary: StateFlow<HistorySummary> = transferRepository.transferHistory
        .combine(_historyFilter) { historyList, _ ->
            HistorySummary(
                totalFiles = historyList.size,
                totalBytes = historyList.filter { it.status == TransferStatus.COMPLETED }.sumOf { it.fileSize },
                successfulCount = historyList.count { it.status == TransferStatus.COMPLETED },
                failedCount = historyList.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistorySummary())

    fun selectTab(tab: TransfersTab) {
        _selectedTab.value = tab
    }

    fun selectCategory(category: TransferCategory) {
        _selectedCategory.value = category
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _historyFilter.value = filter
    }

    fun setHistorySort(sort: HistorySort) {
        _historySort.value = sort
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getTransferById(id: String): TransferItem? {
        return allTransfers.value.find { it.id == id }
            ?: transferRepository.transferHistory.value.find { it.id == id }
    }

    fun pauseTransfer(id: String) = transferManager.pauseTransfer(id)
    fun resumeTransfer(id: String) = transferManager.resumeTransfer(id)
    fun cancelTransfer(id: String) = transferRepository.cancelTransfer(id)
    fun retryTransfer(id: String) = transferRepository.retryTransfer(id)
    fun clearCompleted() = transferRepository.clearCompleted()

    fun pauseAll() = transferRepository.pauseAll()
    fun resumeAll() = transferRepository.resumeAll()
    fun cancelAll() = transferRepository.cancelAll()
    fun retryAllFailed() = transferRepository.retryAllFailed()
    fun moveUp(id: String) = transferRepository.moveTransferUp(id)
    fun moveDown(id: String) = transferRepository.moveTransferDown(id)

    fun cyclePriority(item: TransferItem) {
        val next = when (item.priority) {
            TransferPriority.NORMAL -> TransferPriority.HIGH
            TransferPriority.HIGH -> TransferPriority.LOW
            TransferPriority.LOW -> TransferPriority.NORMAL
        }
        transferRepository.setTransferPriority(item.id, next)
    }

    fun deleteHistoryEntry(id: String) {
        transferRepository.deleteHistoryEntry(id)
    }

    fun deleteHistoryEntryWithFile(item: TransferItem) {
        transferRepository.deleteHistoryEntryWithFile(item)
    }

    fun clearAllHistory() {
        transferRepository.clearAll()
    }

    fun openFile(item: TransferItem) {
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            storageManager.openFilePath(path)
        }
    }

    fun shareFile(item: TransferItem) {
        val path = item.filePath
        if (!path.isNullOrBlank()) {
            storageManager.shareFilePath(path, item.fileName)
        }
    }

    fun shareMultipleCompleted(items: List<TransferItem>): Boolean {
        val paths = items.mapNotNull { it.filePath?.takeIf { p -> p.isNotBlank() } }
        return if (paths.isNotEmpty()) {
            storageManager.shareMultiplePaths(paths)
        } else {
            false
        }
    }
}
