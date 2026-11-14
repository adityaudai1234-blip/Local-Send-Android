package com.example.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class StorageManager(private val context: Context) {

    @Volatile
    private var customTreeUri: String? = null

    fun setCustomDownloadTreeUri(uriString: String?) {
        customTreeUri = uriString
    }

    private val downloadsDir: File
        get() {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val localDropDir = File(publicDir, "LocalDrop")
            if (!localDropDir.exists()) {
                val created = localDropDir.mkdirs()
                if (!created) {
                    return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                }
            }
            return localDropDir
        }

    private val tempDir: File
        get() {
            val dir = File(context.cacheDir, "transfer_temp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getDownloadDirectory(): File = downloadsDir

    fun createTempFile(fileId: String): File {
        val safeId = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(tempDir, "incoming_$safeId.tmp")
    }

    sealed interface DestinationResolution {
        data class Ready(val file: File) : DestinationResolution
        data object Skipped : DestinationResolution
    }

    /**
     * Resolves duplicate filenames according to user preference ("rename", "overwrite", "skip").
     */
    fun resolveDestinationWithPolicy(fileName: String, duplicateHandling: String): DestinationResolution {
        val sanitized = FileMetadata.sanitizeFileName(fileName)
        val baseDir = downloadsDir
        val target = File(baseDir, sanitized)

        val canonicalBase = baseDir.canonicalPath
        if (!target.canonicalPath.startsWith(canonicalBase)) {
            throw SecurityException("Invalid destination path rejected")
        }

        if (!target.exists()) {
            return DestinationResolution.Ready(target)
        }

        return when (duplicateHandling.lowercase(Locale.ROOT)) {
            "overwrite" -> DestinationResolution.Ready(target)
            "skip" -> DestinationResolution.Skipped
            else -> DestinationResolution.Ready(resolveUniqueDestinationFile(sanitized))
        }
    }

    fun resolveUniqueDestinationFile(fileName: String): File {
        val sanitized = FileMetadata.sanitizeFileName(fileName)
        val target = File(downloadsDir, sanitized)
        if (!target.exists()) return target

        val nameWithoutExt = sanitized.substringBeforeLast('.')
        val ext = if (sanitized.contains('.')) ".${sanitized.substringAfterLast('.')}" else ""

        var counter = 1
        var candidate: File
        do {
            candidate = File(downloadsDir, "$nameWithoutExt ($counter)$ext")
            counter++
        } while (candidate.exists() && counter < 1000)

        return candidate
    }

    /**
     * Moves a verified temporary file to the final destination file,
     * or writes into the user's SAF tree URI if configured.
     */
    fun finalizeReceivedFile(
        tempFile: File,
        destinationFile: File,
        mimeType: String = "application/octet-stream"
    ): String? {
        val customUriStr = customTreeUri
        if (!customUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    mimeType,
                    destinationFile.name
                )
                if (createdUri != null) {
                    context.contentResolver.openOutputStream(createdUri)?.use { out ->
                        FileInputStream(tempFile).use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    tempFile.delete()
                    return createdUri.toString()
                }
            } catch (_: Exception) {
                // Fall back to standard downloadsDir below
            }
        }

        return try {
            if (destinationFile.exists()) destinationFile.delete()
            val renamed = tempFile.renameTo(destinationFile)
            if (!renamed) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
            destinationFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun cleanTempFile(tempFile: File?) {
        try {
            tempFile?.delete()
        } catch (_: Exception) {}
    }

    fun listReceivedFiles(): List<File> {
        val dir = downloadsDir
        return dir.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun openFilePath(pathOrUri: String) {
        if (pathOrUri.startsWith("content://")) {
            try {
                val uri = Uri.parse(pathOrUri)
                val mime = context.contentResolver.getType(uri) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        } else {
            val file = File(pathOrUri)
            if (file.exists()) openFile(file)
        }
    }

    fun shareFilePath(pathOrUri: String, displayName: String) {
        if (pathOrUri.startsWith("content://")) {
            try {
                val uri = Uri.parse(pathOrUri)
                val mime = context.contentResolver.getType(uri) ?: "*/*"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Share $displayName").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (_: Exception) {}
        } else {
            val file = File(pathOrUri)
            if (file.exists()) shareFile(file)
        }
    }

    fun openFile(file: File) {
        try {
            val uri = getFileUri(file)
            val mimeType = getMimeType(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val uri = getFileUri(file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun shareFile(file: File) {
        try {
            val uri = getFileUri(file)
            val mimeType = getMimeType(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {}
    }

    fun deleteFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun deleteFilePath(pathOrUri: String?): Boolean {
        if (pathOrUri.isNullOrBlank()) return false
        return try {
            if (pathOrUri.startsWith("content://")) {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(pathOrUri))
            } else {
                File(pathOrUri).delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    fun hasAvailableStorage(bytesRequired: Long): Boolean {
        return try {
            downloadsDir.usableSpace > bytesRequired + (50L * 1024L * 1024L)
        } catch (_: Exception) {
            true
        }
    }

    fun getTotalReceivedStorageBytes(): Long {
        return try {
            listReceivedFiles().sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    fun getTotalTempStorageBytes(): Long {
        return try {
            tempDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun getUsableStorageBytes(): Long {
        return try {
            downloadsDir.usableSpace
        } catch (_: Exception) {
            0L
        }
    }

    fun cleanAllTempFiles(): Int {
        var count = 0
        try {
            tempDir.listFiles()?.forEach {
                if (it.delete()) count++
            }
        } catch (_: Exception) {}
        return count
    }

    fun deleteAllReceivedFiles(): Int {
        var count = 0
        try {
            listReceivedFiles().forEach {
                if (it.delete()) count++
            }
        } catch (_: Exception) {}
        return count
    }
}
