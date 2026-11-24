package com.example.presentation.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.data.transfer.FileMetadata
import com.example.domain.model.TransferItem
import java.io.File

data class FolderSelectionSummary(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val inaccessibleCount: Int
)

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val relativePath: String? = null,
    val dateModified: Long? = null,
    val isDirectory: Boolean = false,
    val isAccessible: Boolean = true,
    val accessError: String? = null
) {
    val formattedSize: String
        get() = if (isDirectory) "Folder" else TransferItem.formatBytes(size)

    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")

    val folderDisplayPath: String?
        get() = relativePath?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }

    companion object {
        fun summarize(files: List<SelectedFile>): FolderSelectionSummary {
            var fileCount = 0
            var dirCount = 0
            var totalBytes = 0L
            var inaccessible = 0
            val folderPaths = HashSet<String>()

            for (item in files) {
                if (item.isDirectory) {
                    dirCount++
                    item.relativePath?.let { folderPaths.add(it) }
                } else {
                    fileCount++
                    totalBytes += item.size.coerceAtLeast(0L)
                    item.folderDisplayPath?.let { folderPaths.add(it) }
                    if (!item.isAccessible) inaccessible++
                }
            }
            return FolderSelectionSummary(
                fileCount = fileCount,
                folderCount = maxOf(dirCount, folderPaths.size),
                totalBytes = totalBytes,
                inaccessibleCount = inaccessible
            )
        }

        fun fromUri(
            context: Context,
            uri: Uri,
            relativePath: String? = null
        ): SelectedFile {
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L
            var lastModified: Long? = null
            var docFlags = 0
            val mimeType = try {
                context.contentResolver.getType(uri) ?: "application/octet-stream"
            } catch (_: SecurityException) {
                "application/octet-stream"
            }

            var accessError: String? = null
            var queryCursorObtained = false
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    queryCursorObtained = true
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
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
                        if (flagsIndex != -1 && !cursor.isNull(flagsIndex)) {
                            docFlags = cursor.getInt(flagsIndex)
                        }
                    }
                }
            } catch (_: SecurityException) {
                accessError = "Permission denied for this file provider"
            } catch (_: Exception) {
                accessError = "Unable to inspect file metadata"
            }

            val isVirtualDoc = (docFlags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
            var afdOpened = false

            if (accessError == null && !isVirtualDoc) {
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        afdOpened = true
                        if (fileSize <= 0L && afd.length > 0L) {
                            fileSize = afd.length
                        }
                    }
                } catch (_: SecurityException) {
                    accessError = "Temporary URI permission expired"
                } catch (_: Exception) {
                    if (uri.scheme == "file") {
                        accessError = "File unavailable or missing"
                    }
                }
            }

            if (accessError == null && uri.scheme == "content" && !queryCursorObtained && !afdOpened) {
                val hasProvider = try {
                    context.contentResolver.acquireUnstableContentProviderClient(uri)?.let { client ->
                        @Suppress("DEPRECATION")
                        client.release()
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
                if (!hasProvider) {
                    accessError = "Content provider unavailable or URI expired"
                }
            }

            // Probe readability when possible without buffering data
            if (accessError == null) {
                val canOpen = try {
                    if (isVirtualDoc) {
                        val altTypes = context.contentResolver.getStreamTypes(uri, "*/*")
                        if (altTypes != null && altTypes.isNotEmpty()) {
                            context.contentResolver.openTypedAssetFileDescriptor(uri, altTypes[0], null)?.use { true } ?: false
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { true } ?: false
                        }
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { true } ?: false
                    }
                } catch (_: SecurityException) {
                    accessError = "Missing read permission"
                    false
                } catch (_: Exception) {
                    if (uri.scheme == "content" || uri.scheme == "file") {
                        accessError = "File unavailable or cloud download required"
                        false
                    } else {
                        true
                    }
                }
                if (!canOpen && accessError == null) {
                    accessError = "File cannot be opened"
                }
            }

            val cleanName = FileMetadata.sanitizeFileName(fileName)
            val cleanRelative = FileMetadata.sanitizeRelativePath(relativePath)

            return SelectedFile(
                uri = uri,
                name = cleanName,
                size = fileSize,
                mimeType = mimeType,
                relativePath = cleanRelative,
                dateModified = lastModified,
                isDirectory = false,
                isAccessible = accessError == null,
                accessError = accessError
            )
        }

        fun createTextNoteFile(context: Context, text: String, subject: String? = null): SelectedFile? {
            if (text.isBlank()) return null
            return try {
                val notesDir = File(context.cacheDir, "shared_notes").apply { if (!exists()) mkdirs() }
                val baseTitle = subject?.takeIf { it.isNotBlank() }
                    ?.let { FileMetadata.sanitizeFileName(it).substringBeforeLast('.') }
                    ?.take(40)
                    ?: "Shared_Note_${System.currentTimeMillis()}"
                val fileName = if (baseTitle.endsWith(".txt", ignoreCase = true)) baseTitle else "$baseTitle.txt"
                val file = File(notesDir, fileName)
                file.writeText(text)
                SelectedFile(
                    uri = Uri.fromFile(file),
                    name = file.name,
                    size = file.length(),
                    mimeType = "text/plain",
                    dateModified = file.lastModified(),
                    isDirectory = false,
                    isAccessible = true
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Recursively traverses an Android SAF tree URI (from OpenDocumentTree)
         * and collects files and empty directory markers with preserved relative paths,
         * guarding against folder reference loops with [visitedDocIds].
         */
        fun fromTreeUri(
            context: Context,
            treeUri: Uri,
            maxFiles: Int = 500
        ): List<SelectedFile> {
            val collected = mutableListOf<SelectedFile>()
            val visitedDocIds = HashSet<String>()
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

                fun walkDirectory(docId: String, docUri: Uri, prefix: String) {
                    if (!visitedDocIds.add(docId)) return
                    if (collected.size >= maxFiles) return
                    var childEntries = 0
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
                            childEntries++

                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                val childDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                walkDirectory(childId, childDirUri, childRelPath)
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
                                        dateModified = mod,
                                        isDirectory = false,
                                        isAccessible = true
                                    )
                                )
                            }
                        }
                    }

                    if (childEntries == 0 && prefix.isNotBlank() && collected.size < maxFiles) {
                        val safeDirRel = FileMetadata.sanitizeRelativePath(prefix)
                        if (safeDirRel != null) {
                            collected.add(
                                SelectedFile(
                                    uri = docUri,
                                    name = safeDirRel.substringAfterLast('/'),
                                    size = 0L,
                                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                                    relativePath = safeDirRel,
                                    isDirectory = true,
                                    isAccessible = true
                                )
                            )
                        }
                    }
                }

                walkDirectory(rootDocId, rootDocUri, FileMetadata.sanitizeFileName(rootFolderName))
            } catch (_: Exception) {
                // Ignore inaccessible SAF documents
            }
            return collected
        }
    }
}
