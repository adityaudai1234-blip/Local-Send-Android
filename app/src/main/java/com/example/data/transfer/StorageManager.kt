package com.example.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class StorageManager(private val context: Context) {

    private val downloadsDir: File
        get() {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val localDropDir = File(publicDir, "LocalDrop")
            if (!localDropDir.exists()) {
                val created = localDropDir.mkdirs()
                if (!created) {
                    // Fallback to app-specific external files dir
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
        return File(tempDir, "incoming_$fileId.tmp")
    }

    /**
     * Resolves duplicate filenames by appending " (1)", " (2)", etc.
     */
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
     * Moves a verified temporary file to the final destination file.
     */
    fun finalizeReceivedFile(tempFile: File, destinationFile: File): Boolean {
        return try {
            if (destinationFile.exists()) destinationFile.delete()
            val renamed = tempFile.renameTo(destinationFile)
            if (!renamed) {
                // If renameTo fails across mounts, copy and delete
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Exception) {
            false
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
        } catch (e: Exception) {
            // Fallback to generic chooser
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
            downloadsDir.usableSpace > bytesRequired + (50 * 1024 * 1024) // 50MB safety cushion
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
