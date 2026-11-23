package com.example.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class PermissionState {
    GRANTED,
    NOT_ASKED,
    RATIONALE_NEEDED,
    PERMANENTLY_DENIED,
    NOT_REQUIRED
}

object PermissionHelper {
    private const val PREFS_NAME = "localdrop_permission_prefs"
    private const val KEY_PREFIX_ASKED = "asked_"
    private const val KEY_DISMISSED_BANNER_PREFIX = "dismissed_banner_"

    fun isNearbyWifiRequiredOnThisDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isNotificationsPermissionRequiredOnThisDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (permission == Manifest.permission.NEARBY_WIFI_DEVICES && !isNearbyWifiRequiredOnThisDevice()) {
            return true
        }
        if (permission == Manifest.permission.POST_NOTIFICATIONS && !isNotificationsPermissionRequiredOnThisDevice()) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun wasPermissionAsked(context: Context, permission: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("$KEY_PREFIX_ASKED$permission", false)
    }

    fun markPermissionAsked(context: Context, permission: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("$KEY_PREFIX_ASKED$permission", true)
            .apply()
    }

    fun markBannerDismissed(context: Context, permission: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("$KEY_DISMISSED_BANNER_PREFIX$permission", true)
            .apply()
    }

    fun isBannerDismissed(context: Context, permission: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("$KEY_DISMISSED_BANNER_PREFIX$permission", false)
    }

    fun getPermissionState(context: Context, permission: String): PermissionState {
        if (permission == Manifest.permission.NEARBY_WIFI_DEVICES && !isNearbyWifiRequiredOnThisDevice()) {
            return PermissionState.NOT_REQUIRED
        }
        if (permission == Manifest.permission.POST_NOTIFICATIONS && !isNotificationsPermissionRequiredOnThisDevice()) {
            return PermissionState.NOT_REQUIRED
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            return PermissionState.GRANTED
        }
        val asked = wasPermissionAsked(context, permission)
        if (!asked) {
            return PermissionState.NOT_ASKED
        }
        val activity = context.findActivity()
        return if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            PermissionState.RATIONALE_NEEDED
        } else if (activity != null) {
            PermissionState.PERMANENTLY_DENIED
        } else {
            PermissionState.RATIONALE_NEEDED
        }
    }

    fun openAppSettings(context: Context) {
        val intent = createAppSettingsIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun createAppSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }

    fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
