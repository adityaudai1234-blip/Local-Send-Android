package com.example.data.protocol

import org.json.JSONObject
import java.util.UUID

data class ProtocolMessage(
    val version: String = "v1",
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
            payloadJson.put(key, value)
        }
        json.put("payload", payloadJson)

        return json.toString()
    }

    companion object {
        val SUPPORTED_VERSIONS = setOf("v1", "v2")

        private fun JSONObject.optNonNullString(key: String, default: String = ""): String {
            if (!has(key) || isNull(key)) return default
            return optString(key, default)
        }

        fun fromJsonString(jsonString: String): ProtocolMessage {
            val json = JSONObject(jsonString)
            val version = json.optNonNullString("version", "v1").ifBlank { "v1" }
            if (version !in SUPPORTED_VERSIONS) {
                throw IllegalArgumentException("Unsupported protocol version: $version")
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
            receiverId: String,
            errorCode: String,
            errorMessage: String
        ): ProtocolMessage {
            return ProtocolMessage(
                type = ProtocolMessageType.ERROR,
                senderId = senderId,
                receiverId = receiverId,
                payload = mapOf(
                    "code" to errorCode,
                    "message" to errorMessage
                )
            )
        }

        fun createHeartbeat(senderId: String): ProtocolMessage {
            return ProtocolMessage(
                type = ProtocolMessageType.HEARTBEAT,
                senderId = senderId,
                payload = mapOf("ping" to System.currentTimeMillis())
            )
        }
    }
}
