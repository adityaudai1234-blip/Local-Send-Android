package com.example.presentation.components

import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.TransferItem
import com.example.util.FilePreviewHelper
import com.example.util.FilePreviewInfo
import com.example.util.PreviewKind
import java.io.File
import java.util.Date

@Composable
fun FilePreviewThumbnail(
    fileName: String,
    mimeType: String,
    uri: Uri? = null,
    file: File? = null,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kind = FilePreviewHelper.detectKind(fileName, mimeType)

    val previewInfo by produceState<FilePreviewInfo?>(
        initialValue = null,
        key1 = fileName,
        key2 = uri ?: file?.absolutePath
    ) {
        value = when {
            kind !in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO, PreviewKind.PDF) -> null
            uri != null -> FilePreviewHelper.loadPreviewForUri(
                context = context,
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                maxThumbSizePx = 180
            )
            file != null && file.exists() -> FilePreviewHelper.loadPreviewForFile(
                file = file,
                mimeType = mimeType,
                maxThumbSizePx = 180
            )
            else -> null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = previewInfo?.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "$fileName thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (kind == PreviewKind.VIDEO) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else {
            Icon(
                imageVector = previewKindIcon(kind),
                contentDescription = kind.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.52f)
            )
        }
    }
}

@Composable
fun FilePreviewDialog(
    fileName: String,
    fileSizeBytes: Long,
    mimeType: String,
    relativeFolder: String? = null,
    uri: Uri? = null,
    file: File? = null,
    onOpen: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val previewInfo by produceState<FilePreviewInfo?>(
        initialValue = null,
        key1 = fileName,
        key2 = uri ?: file?.absolutePath
    ) {
        value = when {
            uri != null -> FilePreviewHelper.loadPreviewForUri(
                context = context,
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                maxThumbSizePx = 720
            )
            file != null && file.exists() -> FilePreviewHelper.loadPreviewForFile(
                file = file,
                mimeType = mimeType,
                maxThumbSizePx = 720
            )
            else -> FilePreviewInfo(
                kind = FilePreviewHelper.detectKind(fileName, mimeType),
                mimeType = mimeType
            )
        }
    }

    val info = previewInfo ?: FilePreviewInfo(
        kind = FilePreviewHelper.detectKind(fileName, mimeType),
        mimeType = mimeType
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("file_preview_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${info.kind.label} • ${TransferItem.formatBytes(fileSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_file_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                when {
                    info.thumbnail != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 300.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = info.thumbnail.asImageBitmap(),
                                contentDescription = "$fileName preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    info.textSnippet != null -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = info.textSnippet,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    else -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = previewKindIcon(info.kind),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = info.title ?: fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = info.subtitle ?: info.kind.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Metadata rows
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PreviewMetaRow("Size", TransferItem.formatBytes(fileSizeBytes))
                        PreviewMetaRow("MIME Type", mimeType)
                        info.dimensions?.let { PreviewMetaRow("Dimensions", it) }
                        info.formattedDuration?.let { PreviewMetaRow("Duration", it) }
                        info.pageCount?.let { PreviewMetaRow("Pages", "$it page(s)") }
                        if (!relativeFolder.isNullOrBlank()) {
                            PreviewMetaRow("Folder", relativeFolder)
                        }
                        info.lastModifiedMs?.let { ts ->
                            val formattedDate = DateFormat.format("MMM d, yyyy • h:mm a", Date(ts)).toString()
                            PreviewMetaRow("Modified", formattedDate)
                        }
                    }
                }

                if (onOpen != null || onShare != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (onShare != null) {
                            OutlinedButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("preview_share_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                        if (onOpen != null) {
                            Button(
                                onClick = onOpen,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("preview_open_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun previewKindIcon(kind: PreviewKind): ImageVector {
    return when (kind) {
        PreviewKind.IMAGE -> Icons.Default.Image
        PreviewKind.VIDEO -> Icons.Default.VideoFile
        PreviewKind.AUDIO -> Icons.Default.AudioFile
        PreviewKind.PDF -> Icons.Default.PictureAsPdf
        PreviewKind.TEXT -> Icons.Default.Code
        PreviewKind.ARCHIVE -> Icons.Default.Archive
        PreviewKind.APK -> Icons.Default.Android
        PreviewKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
