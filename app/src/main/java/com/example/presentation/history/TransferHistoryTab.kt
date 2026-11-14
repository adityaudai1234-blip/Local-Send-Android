package com.example.presentation.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.TransferStatusBadge
import com.example.presentation.transfers.HistoryFilter
import com.example.presentation.transfers.HistorySort
import com.example.presentation.transfers.TransfersViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransferHistoryTab(
    viewModel: TransfersViewModel,
    onOpenDetails: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val history by viewModel.filteredHistory.collectAsState()
    val summary by viewModel.historySummary.collectAsState()
    val selectedFilter by viewModel.historyFilter.collectAsState()
    val selectedSort by viewModel.historySort.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var itemToDeleteFile by remember { mutableStateOf<TransferItem?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("transfer_history_tab"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Summary Cards (2x2 grid)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryMetricCard(
                        title = "Total Files",
                        value = summary.totalFiles.toString(),
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    HistoryMetricCard(
                        title = "Data Transferred",
                        value = summary.formattedTotalData,
                        accent = CyanAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryMetricCard(
                        title = "Successful",
                        value = summary.successfulCount.toString(),
                        accent = StatusSuccess,
                        modifier = Modifier.weight(1f)
                    )
                    HistoryMetricCard(
                        title = "Failed / Cancelled",
                        value = summary.failedCount.toString(),
                        accent = StatusError,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search filename, device, or extension…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search history"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input")
            )
        }

        // 3. Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryFilter.entries.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setHistoryFilter(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("history_filter_${filter.name.lowercase()}")
                    )
                }
            }
        }

        // 4. Sort & Clear All Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(
                        onClick = { showSortMenu = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("history_sort_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = selectedSort.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        HistorySort.entries.forEach { sortOption ->
                            DropdownMenuItem(
                                text = { Text(sortOption.label) },
                                onClick = {
                                    viewModel.setHistorySort(sortOption)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                if (summary.totalFiles > 0) {
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("clear_all_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 5. History List or Empty State
        if (history.isEmpty()) {
            item {
                EmptyStateView(
                    icon = Icons.Default.History,
                    title = if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL) {
                        "No matching transfers"
                    } else {
                        "No transfer history yet"
                    },
                    description = if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL) {
                        "Try adjusting your search query or active filter."
                    } else {
                        "Completed, failed, and cancelled transfers between your devices will appear here automatically."
                    },
                    actionButtonText = if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL) "Reset Filters" else null,
                    onActionClick = {
                        viewModel.setSearchQuery("")
                        viewModel.setHistoryFilter(HistoryFilter.ALL)
                    }
                )
            }
        } else {
            items(
                items = history,
                key = { it.id }
            ) { item ->
                HistoryTransferRow(
                    item = item,
                    onClick = { onOpenDetails(item.id) },
                    onOpenFile = if (item.status == TransferStatus.COMPLETED && !item.filePath.isNullOrBlank()) {
                        { viewModel.openFile(item) }
                    } else null,
                    onShareFile = if (item.status == TransferStatus.COMPLETED && !item.filePath.isNullOrBlank()) {
                        { viewModel.shareFile(item) }
                    } else null,
                    onRetry = if (item.status == TransferStatus.FAILED || item.status == TransferStatus.CANCELLED) {
                        {
                            viewModel.retryTransfer(item.id)
                            onShowMessage("Retrying ${item.fileName}…")
                        }
                    } else null,
                    onDeleteHistoryOnly = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.deleteHistoryEntry(item.id)
                        onShowMessage("Removed entry from history (file kept on device)")
                    },
                    onDeleteWithFile = if (!item.filePath.isNullOrBlank()) {
                        { itemToDeleteFile = item }
                    } else null
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear Transfer History?") },
            text = {
                Text("This clears all saved transfer records from history. Your downloaded files on device storage will not be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.clearAllHistory()
                        showClearAllConfirm = false
                        onShowMessage("Transfer history cleared")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear History")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    itemToDeleteFile?.let { targetItem ->
        AlertDialog(
            onDismissRequest = { itemToDeleteFile = null },
            title = { Text("Delete Physical File?") },
            text = {
                Text("Permanently delete \"${targetItem.fileName}\" from device storage and remove its history entry? This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.deleteHistoryEntryWithFile(targetItem)
                        onShowMessage("Deleted ${targetItem.fileName} from device")
                        itemToDeleteFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete File")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDeleteFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryMetricCard(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryTransferRow(
    item: TransferItem,
    onClick: () -> Unit,
    onOpenFile: (() -> Unit)?,
    onShareFile: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onDeleteHistoryOnly: () -> Unit,
    onDeleteWithFile: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateStr = remember(item.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${item.id}")
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when (item.status) {
                            TransferStatus.COMPLETED -> StatusSuccess.copy(alpha = 0.14f)
                            TransferStatus.FAILED -> StatusError.copy(alpha = 0.14f)
                            TransferStatus.CANCELLED -> StatusWarning.copy(alpha = 0.14f)
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.fileTypeIcon,
                    contentDescription = item.fileExtension,
                    tint = when (item.status) {
                        TransferStatus.COMPLETED -> StatusSuccess
                        TransferStatus.FAILED -> StatusError
                        TransferStatus.CANCELLED -> StatusWarning
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.direction == TransferDirection.SEND) {
                            Icons.AutoMirrored.Filled.ArrowForward
                        } else {
                            Icons.AutoMirrored.Filled.ArrowBack
                        },
                        contentDescription = if (item.direction == TransferDirection.SEND) "Sent" else "Received",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${item.peerDeviceName} • ${item.formattedSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransferStatusBadge(status = item.status)
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("history_menu_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Transfer options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onClick()
                        }
                    )
                    if (onOpenFile != null) {
                        DropdownMenuItem(
                            text = { Text("Open File") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onOpenFile()
                            }
                        )
                    }
                    if (onShareFile != null) {
                        DropdownMenuItem(
                            text = { Text("Share File") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onShareFile()
                            }
                        )
                    }
                    if (onRetry != null) {
                        DropdownMenuItem(
                            text = { Text("Retry Transfer") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRetry()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete History Entry") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDeleteHistoryOnly()
                        }
                    )
                    if (onDeleteWithFile != null) {
                        DropdownMenuItem(
                            text = { Text("Delete File from Device", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteWithFile()
                            }
                        )
                    }
                }
            }
        }
    }
}
