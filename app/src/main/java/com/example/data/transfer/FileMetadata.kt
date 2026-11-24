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

        /**
         * Security: Sanitizes incoming filenames to prevent path traversal attacks.
         * Strips directory paths ("../", "..\\", "/", "\") and preserves only the base file name.
         */
        fun sanitizeFileName(rawName: String): String {
            var name = rawName.replace("\\", "/").substringAfterLast('/')
            name = name.replace("\u0000", "").replace("..", "").trim()
            if (name.isBlank() || name == "." || name == "..") {
                name = "file_${System.currentTimeMillis()}"
            }
            return name
        }

        /**
         * Security: Sanitizes a relative path for folder-structured transfers.
         * Rejects traversal segments (".."), drive letters ("C:"), null bytes, and leading slashes.
         * Returns a normalized POSIX path like "TripPhotos/Day1/photo.jpg" or null if empty/unsafe.
         */
        fun sanitizeRelativePath(rawRelativePath: String?): String? {
            if (rawRelativePath.isNullOrBlank()) return null
            if (rawRelativePath.contains('\u0000')) return null
            val normalized = rawRelativePath.replace("\\", "/").trim().trimStart('/')
            if (normalized.isBlank()) return null

            val segments = normalized.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (segments.isEmpty()) return null
            for (seg in segments) {
                if (seg == "." || seg == ".." || seg.contains("..") || seg.contains(':')) {
                    return null
                }
            }
            return segments.joinToString("/")
        }
    }
}
