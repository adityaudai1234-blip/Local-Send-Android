package com.example.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferPriority
import com.example.domain.model.TransferStatus
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.util.rememberReducedMotion

@Composable
fun TransferItemCard(
    item: TransferItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onShareFile: (() -> Unit)? = null,
    onPauseResume: (() -> Unit)? = null,
    onCyclePriority: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val reducedMotion = rememberReducedMotion()
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "transfer_progress"
    )
    val isPausedState = item.isPaused || item.speed.contains("Pause", ignoreCase = true)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transfer_item_${item.id}")
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Icon Box
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (item.status) {
                                TransferStatus.COMPLETED -> StatusSuccess.copy(alpha = 0.15f)
                                TransferStatus.FAILED -> StatusError.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.primaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.fileTypeIcon,
                        contentDescription = "File",
                        tint = when (item.status) {
                            TransferStatus.COMPLETED -> StatusSuccess
                            TransferStatus.FAILED -> StatusError
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // File Name & Details
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if ((item.status == TransferStatus.PENDING || item.status == TransferStatus.ACTIVE) &&
                            (item.priority != TransferPriority.NORMAL || onCyclePriority != null)
                        ) {
                            Spacer(modifier = Modifier.width(6.dp))
                            PriorityChipBadge(
                                priority = item.priority,
                                onClick = onCyclePriority,
                                modifier = Modifier.testTag("priority_chip_${item.id}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (item.direction == TransferDirection.SEND)
                                Icons.AutoMirrored.Filled.ArrowForward
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (item.direction == TransferDirection.SEND) "Sent" else "Received",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.direction == TransferDirection.SEND)
                                "To ${item.peerDeviceName}"
                            else "From ${item.peerDeviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = " • ${item.formattedSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status Badge & Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransferStatusBadge(status = item.status)

                    if (item.status == TransferStatus.COMPLETED && !item.filePath.isNullOrBlank()) {
                        if (onOpenFile != null) {
                            IconButton(
                                onClick = onOpenFile,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("open_file_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Open file",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (onShareFile != null) {
                            IconButton(
                                onClick = onShareFile,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("share_file_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share file",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else if (item.status == TransferStatus.ACTIVE || item.status == TransferStatus.PENDING) {
                        if (onPauseResume != null) {
                            IconButton(
                                onClick = onPauseResume,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("pause_transfer_${item.id}")
                            ) {
                                Icon(
                                    imageVector = if (isPausedState) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPausedState) "Resume Transfer" else "Pause Transfer",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("cancel_transfer_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Transfer",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (item.status == TransferStatus.FAILED || item.status == TransferStatus.CANCELLED) {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("retry_transfer_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry Transfer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Progress bar and queue order controls
            if (item.status == TransferStatus.ACTIVE || item.status == TransferStatus.PENDING) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append("${(item.progress * 100).toInt()}%")
                            if (item.retryCount > 0) append(" • Retry ${item.retryCount}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.speed.isNotBlank()) {
                            Text(
                                text = item.speed,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onMoveUp != null && item.status == TransferStatus.PENDING) {
                            IconButton(
                                onClick = onMoveUp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("move_up_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Move transfer up in queue",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (onMoveDown != null && item.status == TransferStatus.PENDING) {
                            IconButton(
                                onClick = onMoveDown,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("move_down_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Move transfer down in queue",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!item.errorMessage.isNullOrBlank() && item.status == TransferStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PriorityChipBadge(
    priority: TransferPriority,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val bgColor = when (priority) {
        TransferPriority.HIGH -> StatusWarning.copy(alpha = 0.18f)
        TransferPriority.NORMAL -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        TransferPriority.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (priority) {
        TransferPriority.HIGH -> StatusWarning
        TransferPriority.NORMAL -> MaterialTheme.colorScheme.onPrimaryContainer
        TransferPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (priority) {
        TransferPriority.HIGH -> "High"
        TransferPriority.NORMAL -> "Normal"
        TransferPriority.LOW -> "Low"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = modifier
            .heightIn(min = 24.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
