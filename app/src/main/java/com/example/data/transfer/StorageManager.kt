package com.example.data.transfer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

enum class ReceivedFileCategory(val label: String, val folderName: String) {
    ALL("All", ""),
    IMAGES("Images", "Images"),
    VIDEOS("Videos", "Videos"),
    AUDIO("Audio", "Audio"),
    DOCUMENTS("Documents", "Documents"),
    ARCHIVES("Archives", "Archives"),
    APPLICATIONS("Applications", "Applications"),
    OTHER("Other", "Other")
}

data class ReceivedFileEntry(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val category: ReceivedFileCategory,
    val relativeFolder: String,
    val mimeType: String
)

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
     * Resolves duplicate filenames according to user preference ("rename", "overwrite", "skip"),
     * optionally preserving folder hierarchy via [relativePath] or auto-organizing into type subfolders.
     */
    fun resolveDestinationWithPolicy(
        fileName: String,
        duplicateHandling: String,
        relativePath: String? = null,
        organizeByType: Boolean = false,
        mimeType: String? = null
    ): DestinationResolution {
        val sanitizedName = FileMetadata.sanitizeFileName(fileName)
        val baseDir = downloadsDir
        val canonicalBase = baseDir.canonicalPath

        val safeRel = FileMetadata.sanitizeRelativePath(relativePath)
        val relParent = safeRel?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }

        val targetParent = when {
            relParent != null -> File(baseDir, relParent)
            organizeByType -> {
                val category = categorizeFile(sanitizedName, mimeType)
                File(baseDir, category.folderName)
            }
            else -> baseDir
        }

        val canonicalParent = targetParent.canonicalPath
        if (!canonicalParent.startsWith(canonicalBase)) {
            throw SecurityException("Invalid subfolder destination rejected")
        }
        if (!targetParent.exists()) {
            targetParent.mkdirs()
        }

        val target = File(targetParent, sanitizedName)
        if (!target.canonicalPath.startsWith(canonicalBase)) {
            throw SecurityException("Invalid destination path rejected")
        }

        if (!target.exists()) {
            return DestinationResolution.Ready(target)
        }

        return when (duplicateHandling.lowercase(Locale.ROOT)) {
            "overwrite" -> DestinationResolution.Ready(target)
            "skip" -> DestinationResolution.Skipped
            else -> DestinationResolution.Ready(resolveUniqueDestinationFileInDir(targetParent, sanitizedName))
        }
    }

    fun resolveUniqueDestinationFile(fileName: String): File {
        val sanitized = FileMetadata.sanitizeFileName(fileName)
        return resolveUniqueDestinationFileInDir(downloadsDir, sanitized)
    }

    private fun resolveUniqueDestinationFileInDir(parentDir: File, sanitizedFileName: String): File {
        val target = File(parentDir, sanitizedFileName)
        if (!target.exists()) return target

        val nameWithoutExt = sanitizedFileName.substringBeforeLast('.')
        val ext = if (sanitizedFileName.contains('.')) ".${sanitizedFileName.substringAfterLast('.')}" else ""

        var counter = 1
        var candidate: File
        do {
            candidate = File(parentDir, "$nameWithoutExt ($counter)$ext")
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
            destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
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

    fun cleanStaleTempFiles(maxAgeMs: Long = 24L * 60L * 60L * 1000L): Int {
        var count = 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        try {
            tempDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff && file.delete()) {
                    count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun listReceivedFiles(): List<File> {
        val dir = downloadsDir
        val collected = mutableListOf<File>()
        fun collect(folder: File) {
            folder.listFiles()?.forEach { entry ->
                if (entry.isDirectory) {
                    collect(entry)
                } else if (entry.isFile && !entry.name.endsWith(".tmp")) {
                    collected.add(entry)
                }
            }
        }
        collect(dir)
        return collected.sortedByDescending { it.lastModified() }
    }

    fun listReceivedFileEntries(): List<ReceivedFileEntry> {
        val basePath = downloadsDir.absolutePath
        return listReceivedFiles().map { file ->
            val mime = getMimeType(file)
            val relFolder = file.parentFile?.absolutePath
                ?.removePrefix(basePath)
                ?.trimStart(File.separatorChar)
                ?: ""
            ReceivedFileEntry(
                file = file,
                name = file.name,
                size = file.length(),
                lastModified = file.lastModified(),
                category = categorizeFile(file.name, mime),
                relativeFolder = relFolder,
                mimeType = mime
            )
        }
    }

    fun shareQrBitmap(bitmap: Bitmap): Boolean {
        return try {
            val qrDir = File(context.cacheDir, "shared_qr").apply { if (!exists()) mkdirs() }
            val qrFile = File(qrDir, "localdrop_pairing_qr.png")
            FileOutputStream(qrFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = getFileUri(qrFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share LocalDrop Pairing QR").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveQrBitmapToGallery(bitmap: Bitmap, fileName: String = "LocalDrop_QR_${System.currentTimeMillis()}.png"): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LocalDrop")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Pictures/LocalDrop/$fileName"
            } else {
                val picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val outDir = File(picsDir, "LocalDrop").apply { if (!exists()) mkdirs() }
                val outFile = File(outDir, fileName)
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                outFile.absolutePath
            }
        } catch (_: Exception) {
            // App-internal fallback if external gallery write is unavailable
            try {
                val fallbackDir = File(downloadsDir, "QR").apply { if (!exists()) mkdirs() }
                val fallbackFile = File(fallbackDir, fileName)
                FileOutputStream(fallbackFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                fallbackFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }
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

    fun getMimeType(file: File): String {
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

    companion object {
        fun categorizeFile(fileName: String, mimeType: String? = null): ReceivedFileCategory {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
            return when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic", "heif") ->
                    ReceivedFileCategory.IMAGES
                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp") ->
                    ReceivedFileCategory.VIDEOS
                mime.startsWith("audio/") || ext in setOf("mp3", "wav", "flac", "m4a", "ogg", "aac", "opus") ->
                    ReceivedFileCategory.AUDIO
                mime == "application/pdf" || mime.startsWith("text/") || ext in setOf(
                    "pdf", "doc", "docx", "txt", "md", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "json", "xml"
                ) -> ReceivedFileCategory.DOCUMENTS
                ext in setOf("zip", "tar", "gz", "rar", "7z", "bz2", "xz", "tgz") ->
                    ReceivedFileCategory.ARCHIVES
                ext in setOf("apk", "apks", "aab", "exe", "msi", "dmg", "deb", "rpm", "appimage") ->
                    ReceivedFileCategory.APPLICATIONS
                else -> ReceivedFileCategory.OTHER
            }
        }
    }
}
