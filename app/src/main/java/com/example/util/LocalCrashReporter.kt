package com.example.util

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.domain.model.AppErrorSanitizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash & failure diagnostic recorder.
 * Never transmits telemetry externally; strips tokens/secrets before persisting to app-private filesDir.
 */
object LocalCrashReporter {
    private const val TAG = "LocalCrashReporter"
    private const val CRASH_LOG_FILE = "localdrop_crash_diagnostics.log"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appCtx = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordCrash(appCtx, thread.name, throwable)
                } catch (_: Throwable) {
                    // Never interfere with system exception propagation
                } finally {
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }

    fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sanitizedMessage = AppErrorSanitizer.redact(throwable.localizedMessage ?: throwable.message)
            val topFrames = throwable.stackTrace.take(8).joinToString("\n") { frame ->
                "  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
            }
            val report = buildString {
                appendLine("Timestamp: $timestamp")
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Thread: $threadName")
                appendLine("Exception: ${throwable.javaClass.name}: $sanitizedMessage")
                appendLine(topFrames)
            }
            File(context.filesDir, CRASH_LOG_FILE).writeText(report)
            Log.e(TAG, "Recorded local crash diagnostic ($timestamp): ${throwable.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing crash diagnostic: ${e.message}")
        }
    }

    fun readLastCrashSummary(context: Context): String? {
        return try {
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (file.exists() && file.length() > 0L) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearLastCrashSummary(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (file.exists()) file.delete() else true
        } catch (_: Exception) {
            false
        }
    }
}
