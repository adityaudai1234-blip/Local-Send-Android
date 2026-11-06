package com.example.presentation.transfers

import androidx.lifecycle.ViewModel
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

class TransfersViewModel(
    private val transferRepository: TransferRepository
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

    fun selectCategory(category: TransferCategory) {
        _selectedCategory.value = category
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

    fun resetMockData() {
        transferRepository.populateSampleTransfers()
    }
}
