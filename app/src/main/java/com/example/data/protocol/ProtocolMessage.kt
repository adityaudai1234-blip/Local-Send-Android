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
        fun fromJsonString(jsonString: String): ProtocolMessage {
            val json = JSONObject(jsonString)
            val version = json.optString("version", "v1")
            val typeStr = json.getString("type")
            val type = ProtocolMessageType.fromString(typeStr)
            val messageId = json.optString("messageId", UUID.randomUUID().toString())
            val senderId = json.optString("senderId", "")
            val receiverId = json.optString("receiverId", "")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

            val payloadMap = mutableMapOf<String, Any?>()
            val payloadJson = json.optJSONObject("payload")
            if (payloadJson != null) {
                val keys = payloadJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    payloadMap[key] = payloadJson.opt(key)
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
