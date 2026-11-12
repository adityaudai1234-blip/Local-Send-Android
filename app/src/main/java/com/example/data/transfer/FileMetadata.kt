package com.example.data.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class FileMetadata(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val checksum: String = "",
    val offset: Long = 0L
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
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): FileMetadata {
            val rawName = json.optString("fileName", "unnamed_file")
            val sanitized = sanitizeFileName(rawName)
            return FileMetadata(
                fileId = json.optString("fileId", java.util.UUID.randomUUID().toString()),
                fileName = sanitized,
                fileSize = json.optLong("fileSize", 0L),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                checksum = json.optString("checksum", ""),
                offset = json.optLong("offset", 0L)
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
            // Remove any dangerous null bytes or path traversal markers
            name = name.replace("\u0000", "").replace("..", "").trim()
            if (name.isBlank() || name == "." || name == "..") {
                name = "file_${System.currentTimeMillis()}"
            }
            return name
        }
    }
}
