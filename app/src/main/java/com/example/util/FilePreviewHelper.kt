package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class PreviewKind(val label: String) {
    IMAGE("Image"),
    VIDEO("Video"),
    AUDIO("Audio"),
    PDF("PDF Document"),
    TEXT("Text / Code"),
    ARCHIVE("Archive"),
    APK("Android Package"),
    OTHER("File")
}

data class FilePreviewInfo(
    val kind: PreviewKind,
    val thumbnail: Bitmap? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val durationMs: Long? = null,
    val pageCount: Int? = null,
    val dimensions: String? = null,
    val textSnippet: String? = null,
    val mimeType: String = "*/*",
    val lastModifiedMs: Long? = null
) {
    val formattedDuration: String?
        get() {
            val ms = durationMs ?: return null
            if (ms <= 0L) return null
            val totalSeconds = ms / 1000L
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }
}

object FilePreviewHelper {

    fun detectKind(fileName: String, mimeType: String? = null): PreviewKind {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> PreviewKind.IMAGE
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp") -> PreviewKind.VIDEO
            mime.startsWith("audio/") || ext in setOf("mp3", "wav", "flac", "m4a", "ogg", "aac", "opus") -> PreviewKind.AUDIO
            mime == "application/pdf" || ext == "pdf" -> PreviewKind.PDF
            ext in setOf("zip", "tar", "gz", "rar", "7z", "bz2", "xz") -> PreviewKind.ARCHIVE
            ext == "apk" -> PreviewKind.APK
            mime.startsWith("text/") || ext in setOf(
                "txt", "md", "json", "xml", "csv", "log", "kt", "java", "rs", "ts", "js", "py", "html", "css", "yaml", "yml"
            ) -> PreviewKind.TEXT
            else -> PreviewKind.OTHER
        }
    }

    fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    suspend fun loadPreviewForUri(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String,
        maxThumbSizePx: Int = 360
    ): FilePreviewInfo = withContext(Dispatchers.IO) {
        val kind = detectKind(fileName, mimeType)
        when (kind) {
            PreviewKind.IMAGE -> loadImageFromUri(context, uri, mimeType, maxThumbSizePx)
            PreviewKind.VIDEO -> loadVideoFromUri(context, uri, mimeType)
            PreviewKind.AUDIO -> loadAudioFromUri(context, uri, fileName, mimeType)
            PreviewKind.PDF -> loadPdfFromUri(context, uri, mimeType, maxThumbSizePx)
            PreviewKind.TEXT -> loadTextSnippetFromUri(context, uri, mimeType)
            else -> FilePreviewInfo(kind = kind, mimeType = mimeType)
        }
    }

    suspend fun loadPreviewForFile(
        file: File,
        mimeType: String = "*/*",
        maxThumbSizePx: Int = 360
    ): FilePreviewInfo = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext FilePreviewInfo(kind = detectKind(file.name, mimeType), mimeType = mimeType)
        }
        val kind = detectKind(file.name, mimeType)
        val base = when (kind) {
            PreviewKind.IMAGE -> loadImageFromFile(file, mimeType, maxThumbSizePx)
            PreviewKind.VIDEO -> loadVideoFromFile(file, mimeType)
            PreviewKind.AUDIO -> loadAudioFromFile(file, mimeType)
            PreviewKind.PDF -> loadPdfFromFile(file, mimeType, maxThumbSizePx)
            PreviewKind.TEXT -> loadTextSnippetFromFile(file, mimeType)
            else -> FilePreviewInfo(kind = kind, mimeType = mimeType)
        }
        base.copy(lastModifiedMs = file.lastModified())
    }

    private fun loadImageFromUri(
        context: Context,
        uri: Uri,
        mimeType: String,
        maxThumbSizePx: Int
    ): FilePreviewInfo {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val dims = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                "${bounds.outWidth} × ${bounds.outHeight}"
            } else null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxThumbSizePx, maxThumbSizePx)
                } else {
                    4
                }
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
            FilePreviewInfo(
                kind = PreviewKind.IMAGE,
                thumbnail = bitmap,
                dimensions = dims,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.IMAGE, mimeType = mimeType)
        }
    }

    private fun loadImageFromFile(file: File, mimeType: String, maxThumbSizePx: Int): FilePreviewInfo {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val dims = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                "${bounds.outWidth} × ${bounds.outHeight}"
            } else null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxThumbSizePx, maxThumbSizePx)
                } else {
                    4
                }
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            FilePreviewInfo(
                kind = PreviewKind.IMAGE,
                thumbnail = bitmap,
                dimensions = dims,
                mimeType = mimeType
            )
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.IMAGE, mimeType = mimeType)
        }
    }

    private fun loadVideoFromUri(context: Context, uri: Uri, mimeType: String): FilePreviewInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            extractVideoInfo(retriever, mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.VIDEO, mimeType = mimeType)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun loadVideoFromFile(file: File, mimeType: String): FilePreviewInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            extractVideoInfo(retriever, mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.VIDEO, mimeType = mimeType)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractVideoInfo(retriever: MediaMetadataRetriever, mimeType: String): FilePreviewInfo {
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val dims = if (!width.isNullOrBlank() && !height.isNullOrBlank()) "$width × $height" else null
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
            scaleBitmapDown(raw, 360)
        }
        return FilePreviewInfo(
            kind = PreviewKind.VIDEO,
            thumbnail = frame,
            durationMs = duration,
            dimensions = dims,
            mimeType = mimeType
        )
    }

    private fun loadAudioFromUri(context: Context, uri: Uri, fallbackTitle: String, mimeType: String): FilePreviewInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            extractAudioInfo(retriever, fallbackTitle, mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.AUDIO, title = fallbackTitle, mimeType = mimeType)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun loadAudioFromFile(file: File, mimeType: String): FilePreviewInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            extractAudioInfo(retriever, file.name, mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.AUDIO, title = file.name, mimeType = mimeType)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractAudioInfo(retriever: MediaMetadataRetriever, fallbackTitle: String, mimeType: String): FilePreviewInfo {
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        return FilePreviewInfo(
            kind = PreviewKind.AUDIO,
            title = title,
            subtitle = artist,
            durationMs = duration,
            mimeType = mimeType
        )
    }

    private fun loadPdfFromUri(context: Context, uri: Uri, mimeType: String, maxThumbSizePx: Int): FilePreviewInfo {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                renderPdfPreview(pfd, mimeType, maxThumbSizePx)
            } ?: FilePreviewInfo(kind = PreviewKind.PDF, mimeType = mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.PDF, mimeType = mimeType)
        }
    }

    private fun loadPdfFromFile(file: File, mimeType: String, maxThumbSizePx: Int): FilePreviewInfo {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                renderPdfPreview(pfd, mimeType, maxThumbSizePx)
            } ?: FilePreviewInfo(kind = PreviewKind.PDF, mimeType = mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.PDF, mimeType = mimeType)
        }
    }

    private fun renderPdfPreview(
        pfd: ParcelFileDescriptor,
        mimeType: String,
        maxThumbSizePx: Int
    ): FilePreviewInfo {
        val renderer = PdfRenderer(pfd)
        return try {
            val count = renderer.pageCount
            var thumb: Bitmap? = null
            if (count > 0) {
                renderer.openPage(0).use { page ->
                    val ratio = page.width.toFloat() / page.height.coerceAtLeast(1).toFloat()
                    val targetW = if (ratio >= 1f) maxThumbSizePx else (maxThumbSizePx * ratio).toInt().coerceAtLeast(1)
                    val targetH = if (ratio < 1f) maxThumbSizePx else (maxThumbSizePx / ratio).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    thumb = bmp
                }
            }
            FilePreviewInfo(
                kind = PreviewKind.PDF,
                thumbnail = thumb,
                pageCount = count,
                mimeType = mimeType
            )
        } finally {
            try { renderer.close() } catch (_: Exception) {}
        }
    }

    private fun loadTextSnippetFromUri(context: Context, uri: Uri, mimeType: String): FilePreviewInfo {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(1024)
                val read = stream.read(buf)
                if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
            }?.trim()?.takeIf { it.isNotBlank() }
            FilePreviewInfo(kind = PreviewKind.TEXT, textSnippet = text, mimeType = mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.TEXT, mimeType = mimeType)
        }
    }

    private fun loadTextSnippetFromFile(file: File, mimeType: String): FilePreviewInfo {
        return try {
            val text = file.inputStream().use { stream ->
                val buf = ByteArray(1024)
                val read = stream.read(buf)
                if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
            }.trim().takeIf { it.isNotBlank() }
            FilePreviewInfo(kind = PreviewKind.TEXT, textSnippet = text, mimeType = mimeType)
        } catch (_: Exception) {
            FilePreviewInfo(kind = PreviewKind.TEXT, mimeType = mimeType)
        }
    }

    private fun scaleBitmapDown(source: Bitmap, maxEdge: Int): Bitmap {
        val maxDim = maxOf(source.width, source.height)
        if (maxDim <= maxEdge) return source
        val scale = maxEdge.toFloat() / maxDim.toFloat()
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        if (scaled != source) source.recycle()
        return scaled
    }
}
