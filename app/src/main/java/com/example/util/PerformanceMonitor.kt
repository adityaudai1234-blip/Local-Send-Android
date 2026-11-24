package com.example.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

data class PerformanceSnapshot(
    val discoveryDurationMs: Long = 0L,
    val lastConnectionDurationMs: Long = 0L,
    val measuredSendBytesPerSec: Long = 0L,
    val measuredReceiveBytesPerSec: Long = 0L,
    val peakMeasuredSpeedBytesPerSec: Long = 0L,
    val activeSocketCount: Int = 0,
    val totalRetries: Long = 0L,
    val recoveryAttempts: Long = 0L,
    val recoverySuccesses: Long = 0L,
    val usedMemoryBytes: Long = 0L,
    val maxMemoryBytes: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Lightweight, privacy-safe internal telemetry collector tracking measured
 * network/transfer performance without any external analytics or mock metrics.
 */
class PerformanceMonitor {

    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    private val activeSockets = AtomicInteger(0)

    fun recordDiscoveryDuration(durationMs: Long) {
        if (durationMs < 0L) return
        _snapshot.update { current ->
            current.copy(
                discoveryDurationMs = durationMs,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun recordConnectionDuration(durationMs: Long) {
        if (durationMs < 0L) return
        _snapshot.update { current ->
            current.copy(
                lastConnectionDurationMs = durationMs,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun recordMeasuredSendSpeed(bytesPerSec: Long) {
        if (bytesPerSec < 0L) return
        _snapshot.update { current ->
            current.copy(
                measuredSendBytesPerSec = bytesPerSec,
                peakMeasuredSpeedBytesPerSec = maxOf(current.peakMeasuredSpeedBytesPerSec, bytesPerSec),
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun recordMeasuredReceiveSpeed(bytesPerSec: Long) {
        if (bytesPerSec < 0L) return
        _snapshot.update { current ->
            current.copy(
                measuredReceiveBytesPerSec = bytesPerSec,
                peakMeasuredSpeedBytesPerSec = maxOf(current.peakMeasuredSpeedBytesPerSec, bytesPerSec),
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun onSocketOpened() {
        val count = activeSockets.incrementAndGet()
        _snapshot.update { current ->
            current.copy(
                activeSocketCount = count,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun onSocketClosed() {
        val count = activeSockets.decrementAndGet().coerceAtLeast(0)
        if (activeSockets.get() < 0) activeSockets.set(0)
        _snapshot.update { current ->
            current.copy(
                activeSocketCount = count,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun recordRetry() {
        _snapshot.update { current ->
            current.copy(
                totalRetries = current.totalRetries + 1L,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun recordRecoveryAttempt(succeeded: Boolean) {
        _snapshot.update { current ->
            current.copy(
                recoveryAttempts = current.recoveryAttempts + 1L,
                recoverySuccesses = current.recoverySuccesses + if (succeeded) 1L else 0L,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun refreshMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val max = runtime.maxMemory().coerceAtLeast(used)
        _snapshot.update { current ->
            current.copy(
                usedMemoryBytes = used,
                maxMemoryBytes = max,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }
}
