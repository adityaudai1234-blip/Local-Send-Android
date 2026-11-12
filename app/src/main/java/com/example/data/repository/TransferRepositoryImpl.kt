package com.example.data.repository

import com.example.data.db.TransferDao
import com.example.data.db.TransferEntity
import com.example.data.transfer.TransferManager
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class TransferRepositoryImpl(
    private val transferManager: TransferManager,
    private val transferDao: TransferDao
) : TransferRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Merges live active transfers with persistent transfer history from Room
    override val transfers: StateFlow<List<TransferItem>> = combine(
        transferManager.activeTransfers,
        transferManager.transferHistory
    ) { activeList, historyList ->
        val activeIds = activeList.map { it.id }.toSet()
        val filteredHistory = historyList.filter { it.id !in activeIds }
        (activeList + filteredHistory).sortedByDescending { it.timestamp }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val stats: StateFlow<TransferStats> = transferManager.stats

    override fun getTransfersByCategory(category: TransferCategory): List<TransferItem> {
        return transfers.value.filter { it.category == category }
    }

    override fun addTransfer(item: TransferItem) {
        scope.launch {
            transferDao.insert(
                TransferEntity(
                    id = item.id,
                    sessionId = UUID.randomUUID().toString(),
                    fileName = item.fileName,
                    fileSize = item.fileSize,
                    direction = item.direction.name,
                    status = item.status.name,
                    peerDeviceName = item.peerDeviceName,
                    peerAddress = item.peerAddress,
                    filePath = item.filePath,
                    mimeType = item.mimeType,
                    timestamp = item.timestamp,
                    speed = item.speed
                )
            )
        }
    }

    override fun cancelTransfer(id: String) {
        transferManager.cancelTransfer(id)
    }

    override fun retryTransfer(id: String) {
        // Resume if paused/failed, or reset state
        transferManager.resumeTransfer(id)
    }

    override fun clearCompleted() {
        transferManager.clearCompleted()
    }

    override fun clearAll() {
        scope.launch {
            transferDao.deleteAll()
        }
    }

    override fun populateSampleTransfers() {
        val now = System.currentTimeMillis()
        val samples = listOf(
            TransferEntity(
                id = UUID.randomUUID().toString(),
                sessionId = UUID.randomUUID().toString(),
                fileName = "project_design_presentation.pdf",
                fileSize = 24_680_000L,
                direction = TransferDirection.SEND.name,
                status = TransferStatus.COMPLETED.name,
                peerDeviceName = "MacBook Pro M3",
                peerAddress = "192.168.1.189:53317",
                timestamp = now - 15_000L,
                speed = "Done (14.2 MB/s)"
            ),
            TransferEntity(
                id = UUID.randomUUID().toString(),
                sessionId = UUID.randomUUID().toString(),
                fileName = "tauri_build_v2.0_windows.zip",
                fileSize = 142_500_000L,
                direction = TransferDirection.RECEIVE.name,
                status = TransferStatus.COMPLETED.name,
                peerDeviceName = "Tauri Studio PC",
                peerAddress = "192.168.1.142:53317",
                timestamp = now - 120_000L,
                speed = "Done (28.5 MB/s)"
            )
        )
        scope.launch {
            samples.forEach { transferDao.insert(it) }
        }
    }
}
