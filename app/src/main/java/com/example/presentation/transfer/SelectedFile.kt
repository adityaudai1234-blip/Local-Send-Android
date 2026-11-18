package com.example.presentation.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.data.transfer.FileMetadata
import com.example.domain.model.TransferItem

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val relativePath: String? = null,
    val dateModified: Long? = null
) {
    val formattedSize: String
        get() = TransferItem.formatBytes(size)

    val extension: String
        get() = name.substringAfterLast('.', "")

    val folderDisplayPath: String?
        get() = relativePath?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }

    companion object {
        fun fromUri(
            context: Context,
            uri: Uri,
            relativePath: String? = null
        ): SelectedFile {
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L
            var lastModified: Long? = null
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            fileSize = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                        }
                        if (modIndex != -1 && !cursor.isNull(modIndex)) {
                            lastModified = cursor.getLong(modIndex)
                        }
                    }
                }
            } catch (_: Exception) {}

            val cleanName = FileMetadata.sanitizeFileName(fileName)
            val cleanRelative = FileMetadata.sanitizeRelativePath(relativePath)

            return SelectedFile(
                uri = uri,
                name = cleanName,
                size = fileSize,
                mimeType = mimeType,
                relativePath = cleanRelative,
                dateModified = lastModified
            )
        }

        /**
         * Recursively traverses an Android SAF tree URI (from OpenDocumentTree)
         * and collects files with preserved relative subfolder paths.
         */
        fun fromTreeUri(
            context: Context,
            treeUri: Uri,
            maxFiles: Int = 500
        ): List<SelectedFile> {
            val collected = mutableListOf<SelectedFile>()
            try {
                val resolver = context.contentResolver
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

                var rootFolderName = treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() } ?: "SharedFolder"

                resolver.query(
                    rootDocUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (nameIdx != -1) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let {
                                rootFolderName = FileMetadata.sanitizeFileName(it)
                            }
                        }
                    }
                }

                fun walkDirectory(docId: String, prefix: String) {
                    if (collected.size >= maxFiles) return
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    )
                    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                        while (cursor.moveToNext() && collected.size < maxFiles) {
                            val childId = if (idIdx != -1) cursor.getString(idIdx) else continue
                            val rawName = if (nameIdx != -1) cursor.getString(nameIdx) else continue
                            val mime = if (mimeIdx != -1) cursor.getString(mimeIdx) else "application/octet-stream"
                            val cleanSeg = FileMetadata.sanitizeFileName(rawName)
                            val childRelPath = if (prefix.isBlank()) cleanSeg else "$prefix/$cleanSeg"

                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                walkDirectory(childId, childRelPath)
                            } else {
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                                val mod = if (modIdx != -1 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else null
                                val safeRel = FileMetadata.sanitizeRelativePath(childRelPath)
                                collected.add(
                                    SelectedFile(
                                        uri = fileUri,
                                        name = cleanSeg,
                                        size = size,
                                        mimeType = mime ?: "application/octet-stream",
                                        relativePath = safeRel,
                                        dateModified = mod
                                    )
                                )
                            }
                        }
                    }
                }

                walkDirectory(rootDocId, FileMetadata.sanitizeFileName(rootFolderName))
            } catch (_: Exception) {
                // Ignore inaccessible SAF documents
            }
            return collected
        }
    }
}
