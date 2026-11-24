package com.example.data.repository

import com.example.data.db.TransferDao
import com.example.data.db.TransferEntity
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStats
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class TransferRepositoryImpl(
    private val transferManager: TransferManager,
    private val transferDao: TransferDao,
    private val storageManager: StorageManager
) : TransferRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val transferHistory: StateFlow<List<TransferItem>> = transferManager.transferHistory

    // Merges live active/queued transfers with persistent transfer history from Room
    override val transfers: StateFlow<List<TransferItem>> = combine(
        transferManager.activeTransfers,
        transferManager.transferHistory
    ) { activeList, historyList ->
        val activeIds = activeList.map { it.id }.toSet()
        val filteredHistory = historyList.filter { it.id !in activeIds }
        val activeSorted = activeList.sortedWith(
            compareByDescending<TransferItem> { it.priority.weight }
                .thenBy { it.queueOrder }
                .thenByDescending { it.timestamp }
        )
        val historySorted = filteredHistory.sortedByDescending { it.timestamp }
        activeSorted + historySorted
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val stats: StateFlow<TransferStats> = transferManager.stats

    override fun getTransfersByCategory(category: TransferCategory): List<TransferItem> {
        return transfers.value.filter { it.category == category }
    }

    override fun addTransfer(item: TransferItem) {
        scope.launch {
            val sender = item.senderDevice.ifBlank {
                if (item.direction == TransferDirection.SEND) "This Device" else item.peerDeviceName
            }
            val receiver = item.receiverDevice.ifBlank {
                if (item.direction == TransferDirection.SEND) item.peerDeviceName else "This Device"
            }
            transferDao.insert(
                TransferEntity(
                    id = item.id,
                    sessionId = UUID.randomUUID().toString(),
                    fileName = item.fileName,
                    fileExtension = item.fileExtension.ifBlank { item.fileName.substringAfterLast('.', "") },
                    mimeType = item.mimeType,
                    fileSize = item.fileSize,
                    senderDevice = sender,
                    receiverDevice = receiver,
                    direction = item.direction.name,
                    status = item.status.name,
                    startTime = item.startTime,
                    completionTime = item.completionTime,
                    durationMs = item.durationMs,
                    speed = item.speed,
                    checksum = item.checksum,
                    filePath = item.filePath,
                    peerDeviceName = item.peerDeviceName,
                    peerAddress = item.peerAddress,
                    errorMessage = item.errorMessage,
                    timestamp = item.timestamp,
                    relativePath = item.relativePath,
                    avgSpeedBytesPerSec = item.avgSpeedBytesPerSec,
                    peakSpeedBytesPerSec = item.peakSpeedBytesPerSec,
                    priority = item.priority.name,
                    queueOrder = item.queueOrder,
                    bytesTransferred = item.bytesTransferred,
                    isPaused = item.isPaused,
                    retryCount = item.retryCount,
                    isDirectory = item.isDirectory,
                    partialChecksum = item.partialChecksum,
                    sourceUri = item.sourceUri
                )
            )
        }
    }

    override fun cancelTransfer(id: String) = transferManager.cancelTransfer(id)
    override fun retryTransfer(id: String) = transferManager.retryFailedTransfer(id)
    override fun pauseTransfer(id: String) = transferManager.pauseTransfer(id)
    override fun resumeTransfer(id: String) = transferManager.resumeTransfer(id)
    override fun setTransferPriority(id: String, priority: TransferPriority) = transferManager.setTransferPriority(id, priority)
    override fun moveTransferUp(id: String) = transferManager.moveTransferUp(id)
    override fun moveTransferDown(id: String) = transferManager.moveTransferDown(id)
    override fun pauseAll() = transferManager.pauseAllTransfers()
    override fun resumeAll() = transferManager.resumeAllTransfers()
    override fun cancelAll() = transferManager.cancelAllTransfers()
    override fun retryAllFailed() = transferManager.retryAllFailed()
    override fun removeCompletedFromQueue() = transferManager.clearCompleted()

    override fun clearCompleted() {
        transferManager.clearCompleted()
    }

    override fun clearAll() {
        transferManager.clearAllHistory()
    }

    override fun deleteHistoryEntry(id: String) {
        transferManager.deleteHistoryEntry(id)
    }

    override fun deleteHistoryEntryWithFile(item: TransferItem) {
        storageManager.deleteFilePath(item.filePath)
        transferManager.deleteHistoryEntry(item.id)
    }
}
