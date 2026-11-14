package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.di.DefaultAppContainer
import com.example.domain.model.AppSettings
import com.example.domain.model.TransferCategory
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStats
import com.example.domain.model.TransferStatus
import com.example.domain.repository.TransferRepository
import com.example.presentation.transfers.HistoryFilter
import com.example.presentation.transfers.HistorySort
import com.example.presentation.transfers.TransfersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransfersAndSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun appSettings_defaultsEnforceSecurityPolicy() {
        val defaults = AppSettings(deviceName = "Pixel 9")
        assertFalse("autoAcceptTrusted MUST default to false", defaults.autoAcceptTrusted)
        assertTrue(defaults.requireApprovalUnknown)
        assertTrue(defaults.tlsPreferred)
        assertEquals(53317, defaults.port)
        assertEquals("rename", defaults.duplicateHandling)
    }

    @Test
    fun transfersViewModel_filtersSearchesSortsAndSummarizesHistory() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val container = DefaultAppContainer(context)

        val sampleHistory = listOf(
            TransferItem(
                id = "1",
                fileName = "notes.txt",
                fileExtension = "txt",
                fileSize = 512L,
                direction = TransferDirection.SEND,
                status = TransferStatus.COMPLETED,
                peerDeviceName = "MacBook Pro",
                senderDevice = "Pixel",
                receiverDevice = "MacBook Pro",
                timestamp = 1000L
            ),
            TransferItem(
                id = "2",
                fileName = "installer.dmg",
                fileExtension = "dmg",
                fileSize = 50_000_000L,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.COMPLETED,
                peerDeviceName = "MacBook Pro",
                senderDevice = "MacBook Pro",
                receiverDevice = "Pixel",
                timestamp = 3000L
            ),
            TransferItem(
                id = "3",
                fileName = "backup.tar.gz",
                fileExtension = "gz",
                fileSize = 4_000L,
                direction = TransferDirection.SEND,
                status = TransferStatus.FAILED,
                peerDeviceName = "Linux Box",
                senderDevice = "Pixel",
                receiverDevice = "Linux Box",
                timestamp = 2000L
            ),
            TransferItem(
                id = "4",
                fileName = "video.mp4",
                fileExtension = "mp4",
                fileSize = 10_000_000L,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.CANCELLED,
                peerDeviceName = "Windows Rig",
                senderDevice = "Windows Rig",
                receiverDevice = "Pixel",
                timestamp = 4000L
            )
        )

        val fakeRepo = object : TransferRepository {
            override val transfers: StateFlow<List<TransferItem>> = MutableStateFlow(sampleHistory)
            override val transferHistory: StateFlow<List<TransferItem>> = MutableStateFlow(sampleHistory)
            override val stats: StateFlow<TransferStats> = MutableStateFlow(TransferStats())
            override fun getTransfersByCategory(category: TransferCategory) = emptyList<TransferItem>()
            override fun addTransfer(item: TransferItem) {}
            override fun cancelTransfer(id: String) {}
            override fun retryTransfer(id: String) {}
            override fun clearCompleted() {}
            override fun clearAll() {}
            override fun deleteHistoryEntry(id: String) {}
            override fun deleteHistoryEntryWithFile(item: TransferItem) {}
        }

        val viewModel = TransfersViewModel(
            transferRepository = fakeRepo,
            transferManager = container.transferManager,
            storageManager = container.storageManager
        )

        val historyJob = launch { viewModel.filteredHistory.collect {} }
        val summaryJob = launch { viewModel.historySummary.collect {} }
        advanceUntilIdle()

        // Summary counts
        val summary = viewModel.historySummary.value
        assertEquals(4, summary.totalFiles)
        assertEquals(2, summary.successfulCount)
        assertEquals(2, summary.failedCount) // FAILED + CANCELLED
        assertEquals(50_000_512L, summary.totalBytes)

        // Filter SENT
        viewModel.setHistoryFilter(HistoryFilter.SENT)
        advanceUntilIdle()
        assertEquals(listOf("3", "1"), viewModel.filteredHistory.value.map { it.id })

        // Filter CANCELLED
        viewModel.setHistoryFilter(HistoryFilter.CANCELLED)
        advanceUntilIdle()
        assertEquals(listOf("4"), viewModel.filteredHistory.value.map { it.id })

        // Reset filter & sort LARGEST
        viewModel.setHistoryFilter(HistoryFilter.ALL)
        viewModel.setHistorySort(HistorySort.LARGEST)
        advanceUntilIdle()
        assertEquals(listOf("2", "4", "3", "1"), viewModel.filteredHistory.value.map { it.id })

        // Search by device name
        viewModel.setSearchQuery("Linux")
        advanceUntilIdle()
        assertEquals(listOf("3"), viewModel.filteredHistory.value.map { it.id })

        historyJob.cancel()
        summaryJob.cancel()
    }
}
