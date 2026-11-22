package com.example.data.pairing

import com.example.data.security.SecurityValidator
import org.json.JSONObject

data class PairingPayload(
    val protocolVersion: String = "v1",
    val sessionId: String,
    val pairingToken: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val ipAddress: String,
    val port: Int = 53317,
    val expiresAt: Long
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt

    val remainingSeconds: Int
        get() = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()

    fun toJson(): String {
        val json = JSONObject()
        json.put("protocolVersion", protocolVersion)
        json.put("sessionId", sessionId)
        json.put("pairingToken", pairingToken)
        json.put("deviceId", deviceId)
        json.put("deviceName", deviceName)
        json.put("platform", platform)
        json.put("ipAddress", ipAddress)
        json.put("port", port)
        json.put("expiresAt", expiresAt)
        return json.toString()
    }

    companion object {
        private val SUPPORTED_VERSIONS = setOf("v1", "v2")

        private fun JSONObject.reqNonNullString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "").trim()
            return v.takeIf { it.isNotEmpty() }
        }

        fun fromJson(jsonStr: String): PairingPayload? {
            return try {
                val json = JSONObject(jsonStr.trim())
                val protocolVersion = json.reqNonNullString("protocolVersion") ?: return null
                if (protocolVersion !in SUPPORTED_VERSIONS) return null

                val sessionId = json.reqNonNullString("sessionId") ?: return null
                val pairingToken = json.reqNonNullString("pairingToken") ?: return null
                val deviceId = json.reqNonNullString("deviceId") ?: return null
                val deviceName = json.reqNonNullString("deviceName") ?: return null
                val platform = if (json.has("platform") && !json.isNull("platform")) {
                    json.optString("platform", "unknown").ifBlank { "unknown" }
                } else {
                    "unknown"
                }
                val ipAddress = json.reqNonNullString("ipAddress") ?: return null
                val port = if (json.has("port") && !json.isNull("port")) json.getInt("port") else 53317
                if (!SecurityValidator.isValidPort(port)) return null

                if (!json.has("expiresAt") || json.isNull("expiresAt")) return null
                val expiresAt = json.getLong("expiresAt")
                if (expiresAt <= 0L) return null

                PairingPayload(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    pairingToken = pairingToken,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    platform = platform,
                    ipAddress = ipAddress,
                    port = port,
                    expiresAt = expiresAt
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
