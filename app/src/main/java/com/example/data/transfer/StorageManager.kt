package com.example.data.transfer

import android.content.ClipData
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
import com.example.data.security.SecurityValidator
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.ErrorCategory
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

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

data class ResumeMetadata(
    val fileId: String,
    val senderId: String,
    val fileName: String,
    val fileSize: Long,
    val bytesWritten: Long,
    val prefixSha256: String,
    val expectedTotalSha256: String? = null,
    val relativePath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("fileId", fileId)
        obj.put("senderId", senderId)
        obj.put("fileName", fileName)
        obj.put("fileSize", fileSize)
        obj.put("bytesWritten", bytesWritten)
        obj.put("prefixSha256", prefixSha256)
        if (expectedTotalSha256 != null) obj.put("expectedTotalSha256", expectedTotalSha256)
        if (relativePath != null) obj.put("relativePath", relativePath)
        obj.put("updatedAt", updatedAt)
        return obj.toString()
    }

    companion object {
        fun fromJsonString(raw: String): ResumeMetadata? {
            return try {
                val obj = JSONObject(raw)
                val fileId = obj.optString("fileId", "")
                if (fileId.isBlank()) return null
                ResumeMetadata(
                    fileId = fileId,
                    senderId = obj.optString("senderId", ""),
                    fileName = obj.optString("fileName", ""),
                    fileSize = obj.optLong("fileSize", 0L),
                    bytesWritten = obj.optLong("bytesWritten", 0L),
                    prefixSha256 = obj.optString("prefixSha256", ""),
                    expectedTotalSha256 = obj.optString("expectedTotalSha256", "").takeIf { it.isNotBlank() },
                    relativePath = obj.optString("relativePath", "").takeIf { it.isNotBlank() },
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

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

    private fun safeIdForTemp(fileId: String): String =
        fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    fun createTempFile(fileId: String): File {
        return File(tempDir, "incoming_${safeIdForTemp(fileId)}.tmp")
    }

    fun getTempMetaFile(fileId: String): File {
        return File(tempDir, "incoming_${safeIdForTemp(fileId)}.meta.json")
    }

    fun readResumeMetadata(fileId: String): ResumeMetadata? {
        return try {
            val metaFile = getTempMetaFile(fileId)
            if (!metaFile.exists()) return null
            ResumeMetadata.fromJsonString(metaFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun writeResumeMetadata(metadata: ResumeMetadata) {
        try {
            val metaFile = getTempMetaFile(metadata.fileId)
            metaFile.writeText(metadata.toJsonString())
        } catch (_: Exception) {}
    }

    fun deleteTempAndMetadata(fileId: String) {
        try {
            createTempFile(fileId).delete()
        } catch (_: Exception) {}
        try {
            getTempMetaFile(fileId).delete()
        } catch (_: Exception) {}
    }

    fun computeFilePrefixSha256(file: File, byteCount: Long): String? {
        if (!file.exists() || byteCount < 0L || file.length() < byteCount) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            if (byteCount == 0L) {
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = byteCount
            FileInputStream(file).use { input ->
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            if (remaining != 0L) return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    sealed interface DestinationResolution {
        data class Ready(val file: File) : DestinationResolution
        data object Skipped : DestinationResolution
    }

    fun peekExistingDestination(
        fileName: String,
        relativePath: String? = null,
        organizeByType: Boolean = false,
        mimeType: String? = null
    ): File? {
        val sanitizedName = FileMetadata.sanitizeFileName(fileName)
        val baseDir = downloadsDir
        val safeRel = FileMetadata.sanitizeRelativePath(relativePath)
        val relParent = safeRel?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }

        val targetParent = when {
            relParent != null -> File(baseDir, relParent)
            organizeByType -> File(baseDir, categorizeFile(sanitizedName, mimeType).folderName)
            else -> baseDir
        }
        if (!SecurityValidator.isCanonicalSubPath(targetParent, baseDir)) {
            throw SecurityException("Invalid subfolder destination rejected")
        }
        val target = File(targetParent, sanitizedName)
        if (!SecurityValidator.isCanonicalSubPath(target, baseDir)) {
            throw SecurityException("Invalid destination path rejected")
        }
        return if (target.exists()) target else findCaseInsensitiveMatch(targetParent, sanitizedName)
    }

    /**
     * Resolves duplicate filenames according to user preference ("rename", "keep_both", "overwrite", "skip"),
     * optionally preserving folder hierarchy via [relativePath] or auto-organizing into type subfolders,
     * including cross-OS case-insensitive sibling collision detection for Windows/macOS parity.
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

        if (!SecurityValidator.isCanonicalSubPath(targetParent, baseDir)) {
            throw SecurityException("Invalid subfolder destination rejected")
        }
        if (!targetParent.exists()) {
            targetParent.mkdirs()
        }

        val target = File(targetParent, sanitizedName)
        if (!SecurityValidator.isCanonicalSubPath(target, baseDir)) {
            throw SecurityException("Invalid destination path rejected")
        }

        val existingConflict = if (target.exists()) target else findCaseInsensitiveMatch(targetParent, sanitizedName)
        if (existingConflict == null) {
            return DestinationResolution.Ready(target)
        }

        return when (duplicateHandling.lowercase(Locale.ROOT)) {
            "overwrite", "replace" -> {
                if (existingConflict.name != sanitizedName) {
                    try {
                        existingConflict.delete()
                    } catch (_: Exception) {}
                }
                DestinationResolution.Ready(target)
            }
            "skip" -> DestinationResolution.Skipped
            "rename", "keep_both" -> DestinationResolution.Ready(resolveUniqueDestinationFileInDir(targetParent, sanitizedName))
            else -> DestinationResolution.Ready(resolveUniqueDestinationFileInDir(targetParent, sanitizedName))
        }
    }

    fun createDirectoryFromRelativePath(relativePath: String?): Boolean {
        val safeRel = FileMetadata.sanitizeRelativePath(relativePath) ?: return false
        val baseDir = downloadsDir
        val targetDir = File(baseDir, safeRel)
        if (!SecurityValidator.isCanonicalSubPath(targetDir, baseDir)) {
            throw SecurityException("Invalid directory destination rejected")
        }
        return if (targetDir.exists()) targetDir.isDirectory else targetDir.mkdirs()
    }

    fun resolveUniqueDestinationFile(fileName: String): File {
        val sanitized = FileMetadata.sanitizeFileName(fileName)
        return resolveUniqueDestinationFileInDir(downloadsDir, sanitized)
    }

    private fun resolveUniqueDestinationFileInDir(parentDir: File, sanitizedFileName: String): File {
        val target = File(parentDir, sanitizedFileName)
        if (!target.exists() && findCaseInsensitiveMatch(parentDir, sanitizedFileName) == null) return target

        val nameWithoutExt = sanitizedFileName.substringBeforeLast('.')
        val ext = if (sanitizedFileName.contains('.')) ".${sanitizedFileName.substringAfterLast('.')}" else ""

        var counter = 1
        var candidate: File
        do {
            val candidateName = "$nameWithoutExt ($counter)$ext"
            candidate = File(parentDir, candidateName)
            counter++
        } while ((candidate.exists() || findCaseInsensitiveMatch(parentDir, candidate.name) != null) && counter < 1000)

        return candidate
    }

    private fun findCaseInsensitiveMatch(parentDir: File, fileName: String): File? {
        if (!parentDir.exists() || !parentDir.isDirectory) return null
        return try {
            parentDir.listFiles()?.firstOrNull { entry ->
                entry.name.equals(fileName, ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Moves a verified temporary file to the final destination file using safe staging
     * so an existing destination is never deleted before replacement succeeds, or writes
     * into the user's SAF tree URI if configured.
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
            } catch (e: Exception) {
                AppErrorReporter.reportThrowable(
                    category = ErrorCategory.STORAGE,
                    throwable = e,
                    targetLabel = "Custom Download Folder"
                )
                // Fall back to standard downloadsDir below
            }
        }

        val parentDir = destinationFile.parentFile ?: downloadsDir
        if (!parentDir.exists()) parentDir.mkdirs()

        // Fast path when destination does not yet exist
        if (!destinationFile.exists()) {
            return try {
                val renamed = tempFile.renameTo(destinationFile)
                if (!renamed) {
                    val staged = File(parentDir, ".stage_${UUID.randomUUID()}_${destinationFile.name}")
                    tempFile.copyTo(staged, overwrite = true)
                    if (!staged.renameTo(destinationFile)) {
                        staged.copyTo(destinationFile, overwrite = true)
                        staged.delete()
                    }
                    tempFile.delete()
                }
                destinationFile.absolutePath
            } catch (e: Exception) {
                AppErrorReporter.reportThrowable(
                    category = ErrorCategory.STORAGE,
                    throwable = e,
                    targetLabel = destinationFile.name
                )
                null
            }
        }

        // Atomic replacement path: stage alongside destination first, swap backup, only then remove backup
        val stagedFile = File(parentDir, ".stage_${UUID.randomUUID()}_${destinationFile.name}")
        val backupFile = File(parentDir, ".bak_${UUID.randomUUID()}_${destinationFile.name}")
        return try {
            val movedToStage = tempFile.renameTo(stagedFile)
            if (!movedToStage) {
                tempFile.copyTo(stagedFile, overwrite = true)
                tempFile.delete()
            }
            if (!destinationFile.renameTo(backupFile)) {
                stagedFile.copyTo(destinationFile, overwrite = true)
                stagedFile.delete()
            } else {
                if (!stagedFile.renameTo(destinationFile)) {
                    // Roll back backup if final rename fails
                    backupFile.renameTo(destinationFile)
                    stagedFile.delete()
                    return null
                }
                backupFile.delete()
            }
            destinationFile.absolutePath
        } catch (e: Exception) {
            if (!destinationFile.exists() && backupFile.exists()) {
                backupFile.renameTo(destinationFile)
            }
            if (stagedFile.exists()) stagedFile.delete()
            AppErrorReporter.reportThrowable(
                category = ErrorCategory.STORAGE,
                throwable = e,
                targetLabel = destinationFile.name
            )
            null
        }
    }

    fun cleanTempFile(tempFile: File?) {
        try {
            tempFile?.delete()
        } catch (_: Exception) {}
    }

    fun cleanOrphanTempFiles(activeTransferIds: Set<String>): Int {
        val protectedPrefixes = activeTransferIds.map { "incoming_${safeIdForTemp(it)}" }.toSet()
        var count = 0
        try {
            tempDir.listFiles()?.forEach { file ->
                val baseName = file.name.substringBeforeLast(".meta.json").substringBeforeLast(".tmp")
                if (baseName !in protectedPrefixes && file.delete()) {
                    count++
                }
            }
        } catch (_: Exception) {}
        return count
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
                } else if (entry.isFile &&
                    !entry.name.endsWith(".tmp") &&
                    !entry.name.startsWith(".stage_") &&
                    !entry.name.startsWith(".bak_")
                ) {
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

    fun shareMultipleFiles(files: List<File>): Boolean {
        val existing = files.filter { it.exists() && it.isFile }
        if (existing.isEmpty()) return false
        if (existing.size == 1) {
            shareFile(existing.first())
            return true
        }
        return try {
            val uris = ArrayList<Uri>(existing.map { getFileUri(it) })
            val commonMime = resolveCommonMimeType(existing.map { getMimeType(it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonMime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "Shared files", uris.first()).apply {
                    for (i in 1 until uris.size) {
                        addItem(ClipData.Item(uris[i]))
                    }
                }
            }
            val chooser = Intent.createChooser(intent, "Share ${existing.size} files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareMultiplePaths(pathsOrUris: List<String>): Boolean {
        val uris = ArrayList<Uri>()
        val mimeTypes = mutableListOf<String>()
        for (raw in pathsOrUris) {
            try {
                if (raw.startsWith("content://")) {
                    val uri = Uri.parse(raw)
                    uris.add(uri)
                    mimeTypes.add(context.contentResolver.getType(uri) ?: "*/*")
                } else {
                    val file = File(raw)
                    if (file.exists() && file.isFile) {
                        uris.add(getFileUri(file))
                        mimeTypes.add(getMimeType(file))
                    }
                }
            } catch (_: Exception) {}
        }
        if (uris.isEmpty()) return false
        if (uris.size == 1) {
            val single = pathsOrUris.first()
            shareFilePath(single, File(single).name)
            return true
        }
        return try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = resolveCommonMimeType(mimeTypes)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "Shared files", uris.first()).apply {
                    for (i in 1 until uris.size) {
                        addItem(ClipData.Item(uris[i]))
                    }
                }
            }
            val chooser = Intent.createChooser(intent, "Share ${uris.size} files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveCommonMimeType(mimes: List<String>): String {
        if (mimes.isEmpty()) return "*/*"
        val distinct = mimes.distinct()
        if (distinct.size == 1) return distinct.first()
        val prefixes = distinct.map { it.substringBefore('/') }.distinct()
        return if (prefixes.size == 1 && prefixes.first() != "*") {
            "${prefixes.first()}/*"
        } else {
            "*/*"
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
