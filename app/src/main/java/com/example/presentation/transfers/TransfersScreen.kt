package com.example.presentation.transfers

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.TransferCategory
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.TransferItemCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess

@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel,
    modifier: Modifier = Modifier
) {
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val transfers by viewModel.currentTransfers.collectAsState(initial = emptyList())
    val stats by viewModel.stats.collectAsState()

    val categories = listOf(
        TransferCategory.ACTIVE to "Active (${stats.activeCount})",
        TransferCategory.COMPLETED to "Completed (${stats.completedCount})",
        TransferCategory.FAILED to "Failed (${stats.failedCount})"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("transfers_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Category Filter Chips Row
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
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("chip_${cat.name.lowercase()}")
                    )
                }
            }
        }

        // Subheader with category action (e.g. Clear completed, Reset mock data)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (selectedCategory) {
                        TransferCategory.ACTIVE -> "Ongoing Local Transfers"
                        TransferCategory.COMPLETED -> "Finished Transferred Files"
                        TransferCategory.FAILED -> "Failed or Cancelled Transfers"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (selectedCategory == TransferCategory.COMPLETED && stats.completedCount > 0) {
                    TextButton(
                        onClick = { viewModel.clearCompleted() },
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
                } else if (transfers.isEmpty()) {
                    TextButton(
                        onClick = { viewModel.resetMockData() },
                        modifier = Modifier.testTag("reset_mock_transfers")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Load Sample",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Transfer Items List or Empty State
        if (transfers.isEmpty()) {
            item {
                EmptyStateView(
                    icon = Icons.Default.HourglassEmpty,
                    title = "No ${selectedCategory.name.lowercase()} transfers",
                    description = when (selectedCategory) {
                        TransferCategory.ACTIVE -> "Select files from the Home screen or peer devices to start local sharing."
                        TransferCategory.COMPLETED -> "Transferred files from desktop and mobile devices will be listed here."
                        TransferCategory.FAILED -> "No interrupted or failed file transfers."
                    },
                    actionButtonText = if (selectedCategory == TransferCategory.ACTIVE) "Load Sample Data" else null,
                    onActionClick = { viewModel.resetMockData() }
                )
            }
        } else {
            items(
                items = transfers,
                key = { it.id }
            ) { item ->
                TransferItemCard(
                    item = item,
                    onCancel = { viewModel.cancelTransfer(item.id) },
                    onRetry = { viewModel.retryTransfer(item.id) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
