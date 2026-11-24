package com.example.data.security

import com.example.data.transfer.FileMetadata
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Security validation utilities for LocalDrop local-network protocol & file safety.
 */
object SecurityValidator {

    private val SUSPICIOUS_EXTENSIONS = setOf(
        "apk", "exe", "msi", "bat", "cmd", "sh", "bash", "ps1",
        "vbs", "js", "jse", "jar", "scr", "com", "pif", "deb", "rpm", "dmg"
    )

    private const val MAX_REQUESTS_PER_WINDOW = 30
    private const val RATE_LIMIT_WINDOW_MS = 10_000L
    private const val MAX_FAILED_AUTH_ATTEMPTS = 5
    private const val AUTH_LOCKOUT_MS = 60_000L

    private val connectionTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val failedAuthAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun sanitizeFileName(rawName: String): String {
        return FileMetadata.sanitizeFileName(rawName)
    }

    fun sanitizeRelativePath(rawRelativePath: String?): String? {
        return FileMetadata.sanitizeRelativePath(rawRelativePath)
    }

    fun isPathSafe(fileName: String): Boolean {
        if (fileName.isBlank()) return false
        if (fileName.contains("..") || fileName.contains('/') || fileName.contains('\\')) return false
        if (fileName.contains('\u0000')) return false
        return true
    }

    fun isRelativePathSafe(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        return FileMetadata.sanitizeRelativePath(relativePath) != null
    }

    /**
     * Verifies [target] resolves inside [baseDir] using canonical paths and a strict
     * directory boundary check so sibling directories like /LocalDropEvil are rejected.
     */
    fun isCanonicalSubPath(target: File, baseDir: File): Boolean {
        return try {
            val baseCanonical = baseDir.canonicalPath.trimEnd(File.separatorChar)
            val targetCanonical = target.canonicalPath
            targetCanonical == baseCanonical || targetCanonical.startsWith(baseCanonical + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Constant-time string comparison to protect session IDs and authentication tokens
     * against timing side channels.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    fun isSuspiciousExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUSPICIOUS_EXTENSIONS
    }

    fun isValidPort(port: Int): Boolean = port in 1024..65535

    fun isFileSizeAllowed(fileSize: Long, maxAcceptedMb: Long): Boolean {
        if (fileSize < 0L) return false
        if (maxAcceptedMb <= 0L) return true
        val maxBytes = maxAcceptedMb * 1024L * 1024L
        return fileSize <= maxBytes
    }

    fun allowIncomingConnection(ipAddress: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = ipAddress.ifBlank { "unknown" }
        val timestamps = connectionTimestamps.getOrPut(key) { mutableListOf() }
        synchronized(timestamps) {
            val cutoff = now - RATE_LIMIT_WINDOW_MS
            timestamps.removeAll { it < cutoff }
            if (timestamps.size >= MAX_REQUESTS_PER_WINDOW) {
                return false
            }
            timestamps.add(now)
            return true
        }
    }

    fun isPeerAuthLockedOut(peerId: String, now: Long = System.currentTimeMillis()): Boolean {
        val record = failedAuthAttempts[peerId] ?: return false
        val (attempts, lastFailTime) = record
        if (now - lastFailTime > AUTH_LOCKOUT_MS) {
            failedAuthAttempts.remove(peerId)
            return false
        }
        return attempts >= MAX_FAILED_AUTH_ATTEMPTS
    }

    fun recordAuthResult(peerId: String, success: Boolean, now: Long = System.currentTimeMillis()) {
        if (success) {
            failedAuthAttempts.remove(peerId)
        } else {
            val current = failedAuthAttempts[peerId]
            val nextCount = if (current == null || now - current.second > AUTH_LOCKOUT_MS) 1 else current.first + 1
            failedAuthAttempts[peerId] = nextCount to now
        }
    }

    fun clearRateLimiters() {
        connectionTimestamps.clear()
        failedAuthAttempts.clear()
    }
}
