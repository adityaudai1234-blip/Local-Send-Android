package com.example.domain.repository

import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStats
import kotlinx.coroutines.flow.StateFlow

interface TransferRepository {
    val transfers: StateFlow<List<TransferItem>>
    val transferHistory: StateFlow<List<TransferItem>>
    val stats: StateFlow<TransferStats>

    fun getTransfersByCategory(category: TransferCategory): List<TransferItem>
    fun addTransfer(item: TransferItem)
    fun cancelTransfer(id: String)
    fun retryTransfer(id: String)
    fun pauseTransfer(id: String) {}
    fun resumeTransfer(id: String) {}
    fun setTransferPriority(id: String, priority: TransferPriority) {}
    fun moveTransferUp(id: String) {}
    fun moveTransferDown(id: String) {}
    fun pauseAll() {}
    fun resumeAll() {}
    fun cancelAll() {}
    fun retryAllFailed() {}
    fun removeCompletedFromQueue() {}
    fun clearCompleted()
    fun clearAll()
    fun deleteHistoryEntry(id: String)
    fun deleteHistoryEntryWithFile(item: TransferItem)
}
