package com.example.domain.repository

import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import kotlinx.coroutines.flow.StateFlow

interface TransferRepository {
    val transfers: StateFlow<List<TransferItem>>
    val stats: StateFlow<TransferStats>

    fun getTransfersByCategory(category: TransferCategory): List<TransferItem>
    fun addTransfer(item: TransferItem)
    fun cancelTransfer(id: String)
    fun retryTransfer(id: String)
    fun clearCompleted()
    fun clearAll()
    fun populateSampleTransfers()
}
