package com.example.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

enum class TransferDirection {
    SEND,
    RECEIVE
}

enum class TransferStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TransferCategory {
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class TransferPriority(val weight: Int, val label: String) {
    HIGH(3, "High"),
    NORMAL(2, "Normal"),
    LOW(1, "Low")
}

data class TransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val progress: Float = 0f, // 0.0f to 1.0f
    val speed: String = "",
    val peerDeviceName: String,
    val peerAddress: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val filePath: String? = null,
    val mimeType: String? = null,
    val senderDevice: String = "",
    val receiverDevice: String = "",
    val startTime: Long = timestamp,
    val completionTime: Long = timestamp,
    val durationMs: Long = 0L,
    val checksum: String? = null,
    val fileExtension: String = fileName.substringAfterLast('.', ""),
    val relativePath: String? = null,
    val avgSpeedBytesPerSec: Long = 0L,
    val peakSpeedBytesPerSec: Long = 0L,
    val priority: TransferPriority = TransferPriority.NORMAL,
    val queueOrder: Int = 0,
    val bytesTransferred: Long = 0L,
    val isPaused: Boolean = false,
    val retryCount: Int = 0,
    val isDirectory: Boolean = false,
    val partialChecksum: String? = null,
    val sourceUri: String? = null
) {
    val category: TransferCategory
        get() = when (status) {
            TransferStatus.PENDING, TransferStatus.ACTIVE -> TransferCategory.ACTIVE
            TransferStatus.COMPLETED -> TransferCategory.COMPLETED
            TransferStatus.FAILED, TransferStatus.CANCELLED -> TransferCategory.FAILED
        }

    val formattedSize: String
        get() = formatBytes(fileSize)

    val formattedDuration: String
        get() {
            if (durationMs <= 0L) return "< 1s"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        }

    val fileTypeIcon: ImageVector
        get() {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp" -> Icons.Default.Image
                "mp4", "mkv", "mov", "avi", "webm" -> Icons.Default.VideoFile
                "mp3", "wav", "flac", "m4a", "ogg", "aac" -> Icons.Default.AudioFile
                "zip", "tar", "gz", "rar", "7z" -> Icons.Default.Archive
                "kt", "java", "rs", "ts", "js", "html", "css", "py", "c", "cpp", "json" -> Icons.Default.Code
                "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx" -> Icons.Default.Description
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
        }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }
    }
}
