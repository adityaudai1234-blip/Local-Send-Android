package com.example.presentation.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.TransferItem

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
) {
    val formattedSize: String
        get() = TransferItem.formatBytes(size)

    val extension: String
        get() = name.substringAfterLast('.', "")

    companion object {
        fun fromUri(context: Context, uri: Uri): SelectedFile {
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                        if (sizeIndex != -1) {
                            fileSize = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                        }
                    }
                }
            } catch (_: Exception) {}

            return SelectedFile(
                uri = uri,
                name = fileName,
                size = fileSize,
                mimeType = mimeType
            )
        }
    }
}
