package com.example.data.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class UnsupportedProtocolVersionException(
    val requestedVersion: String,
    message: String = "Unsupported protocol version: $requestedVersion"
) : IllegalArgumentException(message)

data class ProtocolMessage(
    val version: String = ProtocolConstants.PREFERRED_VERSION,
    val type: ProtocolMessageType,
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any?> = emptyMap()
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("type", type.name)
        json.put("messageId", messageId)
        json.put("senderId", senderId)
        json.put("receiverId", receiverId)
        json.put("timestamp", timestamp)

        val payloadJson = JSONObject()
        for ((key, value) in payload) {
            payloadJson.put(key, toJsonCompatible(value))
        }
        json.put("payload", payloadJson)

        return json.toString()
    }

    companion object {
        val SUPPORTED_VERSIONS = ProtocolConstants.SUPPORTED_VERSIONS

        private fun toJsonCompatible(value: Any?): Any {
            return when (value) {
                null -> JSONObject.NULL
                is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
                is List<*> -> JSONArray().apply { value.forEach { put(toJsonCompatible(it)) } }
                is Map<*, *> -> JSONObject().apply {
                    value.forEach { (k, v) -> if (k != null) put(k.toString(), toJsonCompatible(v)) }
                }
                else -> value.toString()
            }
        }

        private fun JSONObject.optNonNullString(key: String, default: String = ""): String {
            if (!has(key) || isNull(key)) return default
            return optString(key, default)
        }

        fun fromJsonString(jsonString: String): ProtocolMessage {
            val json = JSONObject(jsonString)
            val version = json.optNonNullString("version", ProtocolConstants.VERSION_V1)
                .ifBlank { ProtocolConstants.VERSION_V1 }
            if (version !in SUPPORTED_VERSIONS) {
                throw UnsupportedProtocolVersionException(
                    requestedVersion = version,
                    message = "Unsupported protocol version '$version'. Supported versions: ${ProtocolConstants.SUPPORTED_VERSIONS_LIST.joinToString(", ")}"
                )
            }

            val typeStr = json.optNonNullString("type").trim()
            if (typeStr.isEmpty()) {
                throw IllegalArgumentException("Missing or blank protocol message type")
            }
            val type = ProtocolMessageType.fromString(typeStr)
            val messageId = json.optNonNullString("messageId", UUID.randomUUID().toString())
                .ifBlank { UUID.randomUUID().toString() }
            val senderId = json.optNonNullString("senderId", "")
            val receiverId = json.optNonNullString("receiverId", "")
            val timestamp = if (json.has("timestamp") && !json.isNull("timestamp")) {
                json.getLong("timestamp")
            } else {
                System.currentTimeMillis()
            }
            if (timestamp < 0L) {
                throw IllegalArgumentException("Negative protocol timestamp: $timestamp")
            }

            val payloadMap = mutableMapOf<String, Any?>()
            val payloadJson = json.optJSONObject("payload")
            if (payloadJson != null) {
                val keys = payloadJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val raw = payloadJson.opt(key)
                    payloadMap[key] = if (raw == JSONObject.NULL) null else raw
                }
            }

            return ProtocolMessage(
                version = version,
                type = type,
                messageId = messageId,
                senderId = senderId,
                receiverId = receiverId,
                timestamp = timestamp,
                payload = payloadMap
            )
        }

        fun createError(
            senderId: String,
            receiverId: String = "",
            errorCode: String,
            errorMessage: String,
            supportedVersions: List<String>? = null,
            details: String? = null,
            version: String = ProtocolConstants.PREFERRED_VERSION
        ): ProtocolMessage {
            val payloadMap = linkedMapOf<String, Any?>(
                "code" to errorCode,
                "message" to errorMessage
            )
            if (!supportedVersions.isNullOrEmpty()) {
                payloadMap["supportedVersions"] = supportedVersions
            }
            if (!details.isNullOrBlank()) {
                payloadMap["details"] = details
            }
            return ProtocolMessage(
                version = version,
                type = ProtocolMessageType.ERROR,
                senderId = senderId,
                receiverId = receiverId,
                payload = payloadMap
            )
        }

        fun createHeartbeat(senderId: String, version: String = ProtocolConstants.PREFERRED_VERSION): ProtocolMessage {
            return ProtocolMessage(
                version = version,
                type = ProtocolMessageType.HEARTBEAT,
                senderId = senderId,
                payload = mapOf("ping" to System.currentTimeMillis())
            )
        }
    }
}
