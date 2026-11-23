package com.example.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

enum class ErrorCategory(val label: String) {
    DISCOVERY("Discovery"),
    PAIRING("Pairing"),
    AUTHENTICATION("Authentication"),
    CONNECTION("Connection"),
    TRANSFER("Transfer"),
    STORAGE("Storage"),
    PERMISSION("Permission"),
    PROTOCOL("Protocol")
}

data class AppError(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: ErrorCategory,
    val userMessage: String,
    val technicalDetails: String? = null,
    val targetLabel: String = "LocalDrop",
    val address: String = "",
    val isRecoverable: Boolean = true
)

object AppErrorSanitizer {
    private val kvSecretRegex = Regex(
        pattern = """(?i)\b(auth[_-]?token|pairing[_-]?token|token|secret|password|private[_-]?key|api[_-]?key)\s*[:=]\s*["']?([^"',\s\}\]]+)["']?"""
    )
    private val jsonSecretRegex = Regex(
        pattern = """(?i)"(authToken|pairingToken|token|secret|password|privateKey)"\s*:\s*"([^"]+)""""
    )
    private val bearerRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val longHexRegex = Regex("""\b[a-fA-F0-9]{32,}\b""")

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = kvSecretRegex.replace(raw) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        cleaned = jsonSecretRegex.replace(cleaned) { match ->
            "\"${match.groupValues[1]}\":\"[REDACTED]\""
        }
        cleaned = bearerRegex.replace(cleaned, "Bearer [REDACTED]")
        cleaned = longHexRegex.replace(cleaned, "[REDACTED_HEX]")
        return cleaned.trim()
    }
}

object AppErrorMapper {

    fun fromThrowable(
        category: ErrorCategory,
        throwable: Throwable?,
        targetLabel: String = "LocalDrop",
        address: String = ""
    ): AppError {
        val rawMsg = AppErrorSanitizer.redact(throwable?.localizedMessage ?: throwable?.message)
        val excName = throwable?.javaClass?.simpleName ?: "Exception"
        val userMessage = when {
            throwable is ConnectException || throwable is NoRouteToHostException ->
                "Unable to connect to this device. Make sure both devices are connected to the same network."
            throwable is SocketTimeoutException ->
                "Connection timed out while waiting for the peer device to respond."
            throwable is UnknownHostException ->
                "Could not resolve the peer's local network address. Refresh discovery and try again."
            throwable is SSLException ->
                "Secure TLS negotiation failed with this peer. Check TLS settings or re-pair."
            throwable is EOFException ->
                "The peer closed the connection unexpectedly before completing the frame."
            throwable is SecurityException ->
                "A required permission or security check blocked this operation."
            throwable is IOException && category == ErrorCategory.STORAGE ->
                "Unable to read or write the selected file. Check available storage space and folder access."
            else -> friendlyFallbackMessage(category, rawMsg)
        }
        val details = buildString {
            append(excName)
            if (rawMsg.isNotBlank()) {
                append(": ").append(rawMsg)
            }
        }
        return AppError(
            category = category,
            userMessage = userMessage,
            technicalDetails = details,
            targetLabel = targetLabel,
            address = address,
            isRecoverable = true
        )
    }

    fun fromMessage(
        category: ErrorCategory,
        userMessage: String,
        technicalDetails: String? = null,
        targetLabel: String = "LocalDrop",
        address: String = "",
        isRecoverable: Boolean = true
    ): AppError {
        return AppError(
            category = category,
            userMessage = AppErrorSanitizer.redact(userMessage),
            technicalDetails = technicalDetails?.let { AppErrorSanitizer.redact(it) }?.takeIf { it.isNotBlank() },
            targetLabel = targetLabel,
            address = address,
            isRecoverable = isRecoverable
        )
    }

    private fun friendlyFallbackMessage(category: ErrorCategory, redactedDetail: String): String {
        val lower = redactedDetail.lowercase()
        return when (category) {
            ErrorCategory.DISCOVERY ->
                "Unable to scan for nearby devices on this Wi-Fi network. Confirm local network access and retry."
            ErrorCategory.PAIRING -> when {
                "expired" in lower -> "This pairing QR code has expired. Generate a fresh QR code and try again."
                "rejected" in lower || "mismatch" in lower -> "Device pairing could not be verified. Re-scan the latest QR code."
                else -> "Pairing did not complete. Ensure both devices are on the same local network."
            }
            ErrorCategory.AUTHENTICATION ->
                "Peer authentication failed. Re-pair the trusted device if its key was reset."
            ErrorCategory.CONNECTION ->
                "Unable to connect to this device. Make sure both devices are connected to the same network."
            ErrorCategory.TRANSFER -> when {
                "checksum" in lower || "sha-256" in lower || "sha256" in lower ->
                    "SHA-256 checksum verification failed because the received data did not match."
                "rejected" in lower || "declined" in lower -> "The receiving device declined or timed out on the transfer request."
                "cancelled" in lower -> "Transfer was cancelled before completion."
                else -> "File transfer was interrupted. Check your local network connection and retry."
            }
            ErrorCategory.STORAGE -> when {
                "space" in lower -> "Not enough free storage space to save the incoming file."
                "path" in lower || "traversal" in lower -> "Incoming file path was blocked by filename safety validation."
                else -> "Storage error occurred while saving or opening the file."
            }
            ErrorCategory.PERMISSION ->
                "Permission required to continue. Grant access or manage permissions in Android Settings."
            ErrorCategory.PROTOCOL -> when {
                "checksum" in lower || "sha-256" in lower || "sha256" in lower ->
                    "SHA-256 checksum verification failed because the received data did not match."
                else -> "Received an unsupported or malformed message from a peer device."
            }
        }
    }
}

object AppErrorReporter {
    private const val MAX_ERRORS = 40

    private val _errors = MutableStateFlow<List<AppError>>(emptyList())
    val errors: StateFlow<List<AppError>> = _errors.asStateFlow()

    fun report(error: AppError) {
        val sanitized = error.copy(
            userMessage = AppErrorSanitizer.redact(error.userMessage),
            technicalDetails = error.technicalDetails?.let { AppErrorSanitizer.redact(it) }
        )
        _errors.update { current ->
            (listOf(sanitized) + current).take(MAX_ERRORS)
        }
    }

    fun report(
        category: ErrorCategory,
        userMessage: String,
        technicalDetails: String? = null,
        targetLabel: String = "LocalDrop",
        address: String = ""
    ) {
        report(
            AppErrorMapper.fromMessage(
                category = category,
                userMessage = userMessage,
                technicalDetails = technicalDetails,
                targetLabel = targetLabel,
                address = address
            )
        )
    }

    fun reportThrowable(
        category: ErrorCategory,
        throwable: Throwable?,
        targetLabel: String = "LocalDrop",
        address: String = ""
    ) {
        report(AppErrorMapper.fromThrowable(category, throwable, targetLabel, address))
    }

    fun clear() {
        _errors.value = emptyList()
    }
}
