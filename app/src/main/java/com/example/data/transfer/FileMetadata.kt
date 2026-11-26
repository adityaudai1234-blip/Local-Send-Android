package com.example.data.transfer

import org.json.JSONArray
import org.json.JSONObject

data class FileMetadata(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val checksum: String = "",
    val offset: Long = 0L,
    val relativePath: String? = null,
    val isDirectory: Boolean = false
) {
    val formattedSize: String
        get() = com.example.domain.model.TransferItem.formatBytes(fileSize)

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("fileId", fileId)
        json.put("fileName", fileName)
        json.put("fileSize", fileSize)
        json.put("mimeType", mimeType)
        json.put("checksum", checksum)
        json.put("offset", offset)
        if (!relativePath.isNullOrBlank()) {
            json.put("relativePath", relativePath)
        }
        if (isDirectory) {
            json.put("isDirectory", true)
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): FileMetadata {
            val rawName = json.optString("fileName", "unnamed_file")
            val sanitized = sanitizeFileName(rawName)
            val rawRel = if (json.has("relativePath")) json.optString("relativePath", "") else ""
            val sanitizedRel = sanitizeRelativePath(rawRel)
            return FileMetadata(
                fileId = json.optString("fileId", java.util.UUID.randomUUID().toString()),
                fileName = sanitized,
                fileSize = json.optLong("fileSize", 0L),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                checksum = json.optString("checksum", ""),
                offset = json.optLong("offset", 0L),
                relativePath = sanitizedRel,
                isDirectory = json.optBoolean("isDirectory", false)
            )
        }

        fun fromJsonList(array: JSONArray?): List<FileMetadata> {
            if (array == null) return emptyList()
            val list = mutableListOf<FileMetadata>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item != null) {
                    list.add(fromJson(item))
                }
            }
            return list
        }

        fun toJsonArray(files: List<FileMetadata>): JSONArray {
            val array = JSONArray()
            files.forEach { array.put(it.toJson()) }
            return array
        }

        private val WINDOWS_RESERVED_BASENAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )

        private fun sanitizeSegmentPortable(rawSegment: String): String {
            var seg = rawSegment
                .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
                .replace("..", "")
                .replace(Regex("[<>:\"/|?*]"), "_")
                .trim()
                .trimEnd('.', ' ')
            if (seg.isEmpty() || seg == "." || seg == "..") return ""
            val baseName = seg.substringBefore('.')
            if (baseName.uppercase(java.util.Locale.ROOT) in WINDOWS_RESERVED_BASENAMES) {
                seg = "_$seg"
            }
            return seg
        }

        /**
         * Security & Cross-OS Portability: Sanitizes incoming filenames to prevent path traversal
         * attacks and normalize Windows/macOS/Linux illegal characters (`<>:"/|?*`, control chars,
         * trailing dots/spaces, DOS reserved names like `CON`, `NUL`, `COM1`), while preserving UTF-8 Unicode.
         */
        fun sanitizeFileName(rawName: String): String {
            val base = rawName.replace("\\", "/").substringAfterLast('/')
            val sanitized = sanitizeSegmentPortable(base)
            return sanitized.ifBlank { "file_${System.currentTimeMillis()}" }
        }

        /**
         * Security & Cross-OS Portability: Sanitizes a relative path for folder-structured transfers.
         * Rejects traversal segments (".."), drive letters ("C:"), null bytes, and leading slashes,
         * and normalizes each path segment across Windows/macOS/Linux filesystems.
         */
        fun sanitizeRelativePath(rawRelativePath: String?): String? {
            if (rawRelativePath.isNullOrBlank()) return null
            if (rawRelativePath.contains('\u0000')) return null
            val normalized = rawRelativePath.replace("\\", "/").trim().trimStart('/')
            if (normalized.isBlank()) return null

            val rawSegments = normalized.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (rawSegments.isEmpty()) return null
            val cleanSegments = mutableListOf<String>()
            for ((index, seg) in rawSegments.withIndex()) {
                val isDrivePrefix = index == 0 && seg.length >= 2 && seg[0].isLetter() && seg[1] == ':'
                if (seg == "." || seg == ".." || isDrivePrefix) {
                    return null
                }
                val cleanSeg = sanitizeSegmentPortable(seg)
                if (cleanSeg.isBlank()) return null
                cleanSegments.add(cleanSeg)
            }
            return cleanSegments.joinToString("/")
        }
    }
}
