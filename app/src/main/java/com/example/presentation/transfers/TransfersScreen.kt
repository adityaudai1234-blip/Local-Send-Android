package com.example.presentation.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.TransferCategory
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.TransferItemCard
import com.example.presentation.history.TransferHistoryTab
import kotlinx.coroutines.launch

@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel,
    onOpenTransferDetails: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val transfers by viewModel.currentTransfers.collectAsState(initial = emptyList())
    val stats by viewModel.stats.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("transfers_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                TransfersTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = when (tab) {
                                    TransfersTab.ACTIVE -> "Live Queue (${stats.activeCount})"
                                    TransfersTab.HISTORY -> "History"
                                },
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag("transfers_tab_${tab.name.lowercase()}")
                    )
                }
            }

            when (selectedTab) {
                TransfersTab.ACTIVE -> {
                    val categories = listOf(
                        TransferCategory.ACTIVE to "Active (${stats.activeCount})",
                        TransferCategory.COMPLETED to "Completed (${stats.completedCount})",
                        TransferCategory.FAILED to "Failed (${stats.failedCount})"
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                categories.forEach { (cat, label) ->
                                    val isSelected = selectedCategory == cat
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.selectCategory(cat) },
                                        label = {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.testTag("chip_${cat.name.lowercase()}")
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (selectedCategory) {
                                        TransferCategory.ACTIVE -> "Ongoing Local Transfers"
                                        TransferCategory.COMPLETED -> "Completed Transfers"
                                        TransferCategory.FAILED -> "Failed or Cancelled Transfers"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (selectedCategory == TransferCategory.COMPLETED && stats.completedCount > 0) {
                                    TextButton(
                                        onClick = {
                                            viewModel.clearCompleted()
                                            scope.launch { snackbarHostState.showSnackbar("Cleared completed entries") }
                                        },
                                        modifier = Modifier.testTag("clear_completed_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        if (transfers.isEmpty()) {
                            item {
                                EmptyStateView(
                                    icon = Icons.Default.HourglassEmpty,
                                    title = "No ${selectedCategory.name.lowercase()} transfers",
                                    description = when (selectedCategory) {
                                        TransferCategory.ACTIVE -> "Select files from the Home screen to start sharing with nearby peers."
                                        TransferCategory.COMPLETED -> "Finished transfers from mobile and desktop peers appear here."
                                        TransferCategory.FAILED -> "No interrupted or failed file transfers."
                                    }
                                )
                            }
                        } else {
                            items(
                                items = transfers,
                                key = { it.id }
                            ) { item ->
                                TransferItemCard(
                                    item = item,
                                    onClick = { onOpenTransferDetails(item.id) },
                                    onOpenFile = if (!item.filePath.isNullOrBlank()) {
                                        { viewModel.openFile(item) }
                                    } else null,
                                    onShareFile = if (!item.filePath.isNullOrBlank()) {
                                        { viewModel.shareFile(item) }
                                    } else null,
                                    onPauseResume = {
                                        if (item.speed.contains("Pause", ignoreCase = true)) {
                                            viewModel.resumeTransfer(item.id)
                                        } else {
                                            viewModel.pauseTransfer(item.id)
                                        }
                                    },
                                    onCancel = { viewModel.cancelTransfer(item.id) },
                                    onRetry = { viewModel.retryTransfer(item.id) }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }

                TransfersTab.HISTORY -> {
                    TransferHistoryTab(
                        viewModel = viewModel,
                        onOpenDetails = onOpenTransferDetails,
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }
        }
    }
}
