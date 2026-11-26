package com.example.data.pairing

import com.example.data.protocol.ProtocolConstants
import com.example.data.security.SecurityValidator
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class PairingPayload(
    val protocolVersion: String = ProtocolConstants.PREFERRED_VERSION,
    val sessionId: String,
    val pairingToken: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val ipAddress: String,
    val port: Int = ProtocolConstants.DEFAULT_PORT,
    val expiresAt: Long,
    val tlsSupported: Boolean = false,
    val certFingerprint: String? = null,
    val nonce: String? = null
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
        json.put("tlsSupported", tlsSupported)
        if (!certFingerprint.isNullOrBlank()) {
            json.put("certFingerprint", certFingerprint)
        }
        if (!nonce.isNullOrBlank()) {
            json.put("nonce", nonce)
        }
        return json.toString()
    }

    /**
     * Compact URI representation (`localdrop://pair?...`) for manual copy/paste or deep-link pairing
     * across desktop and mobile platforms.
     */
    fun toPairingUri(): String {
        fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val params = linkedMapOf(
            "v" to protocolVersion,
            "sid" to sessionId,
            "tok" to pairingToken,
            "did" to deviceId,
            "name" to deviceName,
            "plat" to platform,
            "ip" to ipAddress,
            "port" to port.toString(),
            "exp" to expiresAt.toString(),
            "tls" to if (tlsSupported) "1" else "0"
        )
        if (!certFingerprint.isNullOrBlank()) {
            params["fp"] = certFingerprint
        }
        if (!nonce.isNullOrBlank()) {
            params["n"] = nonce
        }
        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "${ProtocolConstants.PAIRING_URI_SCHEME}://${ProtocolConstants.PAIRING_URI_HOST}?$query"
    }

    companion object {
        private val SUPPORTED_VERSIONS = ProtocolConstants.SUPPORTED_VERSIONS

        private fun JSONObject.reqNonNullString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "").trim()
            return v.takeIf { it.isNotEmpty() }
        }

        fun fromRawInput(raw: String): PairingPayload? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.startsWith("${ProtocolConstants.PAIRING_URI_SCHEME}://", ignoreCase = true)) {
                fromUri(trimmed)
            } else {
                fromJson(trimmed)
            }
        }

        fun fromUri(uriStr: String): PairingPayload? {
            return try {
                val uri = URI(uriStr.trim())
                if (!uri.scheme.equals(ProtocolConstants.PAIRING_URI_SCHEME, ignoreCase = true)) return null
                if (!uri.host.equals(ProtocolConstants.PAIRING_URI_HOST, ignoreCase = true)) return null
                val rawQuery = uri.rawQuery ?: return null
                val params = mutableMapOf<String, String>()
                for (pair in rawQuery.split('&')) {
                    val idx = pair.indexOf('=')
                    if (idx <= 0) continue
                    val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                    val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                    params[key] = value
                }
                val protocolVersion = params["v"]?.trim()?.takeIf { it in SUPPORTED_VERSIONS } ?: return null
                val sessionId = params["sid"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val pairingToken = params["tok"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val deviceId = params["did"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val deviceName = params["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val platform = params["plat"]?.trim()?.ifBlank { "unknown" } ?: "unknown"
                val ipAddress = params["ip"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val port = params["port"]?.toIntOrNull() ?: ProtocolConstants.DEFAULT_PORT
                if (!SecurityValidator.isValidPort(port)) return null
                val expiresAt = params["exp"]?.toLongOrNull() ?: return null
                if (expiresAt <= 0L) return null
                val tlsSupported = params["tls"] == "1" || params["tls"].equals("true", ignoreCase = true)
                val certFingerprint = params["fp"]?.trim()?.takeIf { it.isNotEmpty() }
                val nonce = params["n"]?.trim()?.takeIf { it.isNotEmpty() }

                PairingPayload(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    pairingToken = pairingToken,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    platform = platform,
                    ipAddress = ipAddress,
                    port = port,
                    expiresAt = expiresAt,
                    tlsSupported = tlsSupported,
                    certFingerprint = certFingerprint,
                    nonce = nonce
                )
            } catch (_: Exception) {
                null
            }
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
                val port = if (json.has("port") && !json.isNull("port")) json.getInt("port") else ProtocolConstants.DEFAULT_PORT
                if (!SecurityValidator.isValidPort(port)) return null

                if (!json.has("expiresAt") || json.isNull("expiresAt")) return null
                val expiresAt = json.getLong("expiresAt")
                if (expiresAt <= 0L) return null

                val tlsSupported = json.optBoolean("tlsSupported", false)
                val certFingerprint = json.reqNonNullString("certFingerprint")
                val nonce = json.reqNonNullString("nonce")

                PairingPayload(
                    protocolVersion = protocolVersion,
                    sessionId = sessionId,
                    pairingToken = pairingToken,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    platform = platform,
                    ipAddress = ipAddress,
                    port = port,
                    expiresAt = expiresAt,
                    tlsSupported = tlsSupported,
                    certFingerprint = certFingerprint,
                    nonce = nonce
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
