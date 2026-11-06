package com.example.data.repository

import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferStats
import com.example.domain.repository.TransferRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class TransferRepositoryImpl : TransferRepository {

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    override val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _stats = MutableStateFlow(TransferStats())
    override val stats: StateFlow<TransferStats> = _stats.asStateFlow()

    init {
        populateSampleTransfers()
    }

    override fun getTransfersByCategory(category: TransferCategory): List<TransferItem> {
        return _transfers.value.filter { it.category == category }
    }

    override fun addTransfer(item: TransferItem) {
        _transfers.update { listOf(item) + it }
        recalculateStats()
    }

    override fun cancelTransfer(id: String) {
        _transfers.update { list ->
            list.map { item ->
                if (item.id == id && (item.status == TransferStatus.ACTIVE || item.status == TransferStatus.PENDING)) {
                    item.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user")
                } else item
            }
        }
        recalculateStats()
    }

    override fun retryTransfer(id: String) {
        _transfers.update { list ->
            list.map { item ->
                if (item.id == id && (item.status == TransferStatus.FAILED || item.status == TransferStatus.CANCELLED)) {
                    item.copy(status = TransferStatus.ACTIVE, progress = 0.15f, speed = "8.4 MB/s", errorMessage = null)
                } else item
            }
        }
        recalculateStats()
    }

    override fun clearCompleted() {
        _transfers.update { list ->
            list.filter { it.status != TransferStatus.COMPLETED }
        }
        recalculateStats()
    }

    override fun clearAll() {
        _transfers.value = emptyList()
        recalculateStats()
    }

    override fun populateSampleTransfers() {
        val now = System.currentTimeMillis()
        val sampleItems = listOf(
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = "project_design_presentation.pdf",
                fileSize = 24_680_000L,
                direction = TransferDirection.SEND,
                status = TransferStatus.ACTIVE,
                progress = 0.68f,
                speed = "14.2 MB/s",
                peerDeviceName = "MacBook Pro M3",
                peerAddress = "192.168.1.189:53317",
                timestamp = now - 15_000L
            ),
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = "tauri_build_v2.0_windows.zip",
                fileSize = 142_500_000L,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.ACTIVE,
                progress = 0.42f,
                speed = "28.5 MB/s",
                peerDeviceName = "Tauri Studio PC",
                peerAddress = "192.168.1.142:53317",
                timestamp = now - 35_000L
            ),
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = "vacation_photos_4k.tar.gz",
                fileSize = 512_000_000L,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.COMPLETED,
                progress = 1.0f,
                speed = "Done (31.2 MB/s)",
                peerDeviceName = "Ubuntu Devbox",
                peerAddress = "192.168.1.105:53317",
                timestamp = now - 3_600_000L
            ),
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = "device_keys_backup.json",
                fileSize = 48_500L,
                direction = TransferDirection.SEND,
                status = TransferStatus.COMPLETED,
                progress = 1.0f,
                speed = "Done",
                peerDeviceName = "MacBook Pro M3",
                peerAddress = "192.168.1.189:53317",
                timestamp = now - 7_200_000L
            ),
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = "raw_camera_footage.mp4",
                fileSize = 1_820_000_000L,
                direction = TransferDirection.SEND,
                status = TransferStatus.FAILED,
                progress = 0.88f,
                speed = "Interrupted",
                peerDeviceName = "Living Room TV",
                peerAddress = "192.168.1.200:53317",
                timestamp = now - 14_400_000L,
                errorMessage = "Connection closed by peer (timeout)"
            )
        )
        _transfers.value = sampleItems
        recalculateStats()
    }

    private fun recalculateStats() {
        val current = _transfers.value
        val active = current.count { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING }
        val completed = current.count { it.status == TransferStatus.COMPLETED }
        val failed = current.count { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED }
        val totalBytes = current.filter { it.status == TransferStatus.COMPLETED }.sumOf { it.fileSize }

        _stats.value = TransferStats(
            activeCount = active,
            completedCount = completed,
            failedCount = failed,
            totalFilesTransferred = completed,
            totalBytesTransferred = totalBytes
        )
    }
}
