package com.example.data.pairing

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
        fun fromJson(jsonStr: String): PairingPayload? {
            return try {
                val json = JSONObject(jsonStr.trim())
                val protocolVersion = json.getString("protocolVersion")
                if (protocolVersion != "v1") return null

                val sessionId = json.getString("sessionId")
                val pairingToken = json.getString("pairingToken")
                val deviceId = json.getString("deviceId")
                val deviceName = json.getString("deviceName")
                val platform = json.optString("platform", "unknown")
                val ipAddress = json.getString("ipAddress")
                val port = json.optInt("port", 53317)
                val expiresAt = json.getLong("expiresAt")

                if (sessionId.isBlank() || pairingToken.isBlank() || ipAddress.isBlank()) {
                    return null
                }

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
