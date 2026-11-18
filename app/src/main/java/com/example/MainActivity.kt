package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.presentation.navigation.MainAppScaffold
import com.example.ui.theme.LocalDropTheme
import com.example.widget.WidgetUpdateHelper

object ShareIntentExtractor {
    fun extractSharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    uris.add(it)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { uris.add(it) }
            }
        }
        val clipData = intent.clipData
        if (clipData != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        return uris.toList()
    }
}

class MainActivity : ComponentActivity() {

    private val sharedUrisState = mutableStateOf<List<Uri>>(emptyList())
    private val widgetActionState = mutableStateOf<String?>(null)
    private val widgetTargetIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        val app = application as LocalDropApp
        val settingsRepo = app.container.settingsRepository

        setContent {
            val settings by settingsRepo.settings.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current

            // Battery optimization: pause active mDNS discovery when UI goes to background
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            app.container.nsdDiscoveryManager.startDiscovery()
                            app.container.connectionManager.triggerBoundedReconnect()
                        }
                        Lifecycle.Event.ON_STOP -> {
                            app.container.nsdDiscoveryManager.stopDiscovery()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Request nearby wifi devices / notification permission on Android 13+
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Permissions result handled gracefully */ }

            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (permissionsToRequest.isNotEmpty()) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }

            LocalDropTheme(
                themeMode = settings.themeMode,
                accentColor = settings.accentColor
            ) {
                MainAppScaffold(
                    app = app,
                    sharedUris = sharedUrisState.value,
                    onSharedUrisConsumed = { sharedUrisState.value = emptyList() },
                    widgetAction = widgetActionState.value,
                    widgetTargetDeviceId = widgetTargetIdState.value,
                    onWidgetActionConsumed = {
                        widgetActionState.value = null
                        widgetTargetIdState.value = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val extracted = ShareIntentExtractor.extractSharedUris(intent)
        if (extracted.isNotEmpty()) {
            extracted.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Temporary Share Sheet read grants are still valid for the current Activity session
                }
            }
            sharedUrisState.value = extracted
        }

        val widgetAction = intent.getStringExtra(WidgetUpdateHelper.EXTRA_WIDGET_ACTION)
        if (!widgetAction.isNullOrBlank()) {
            widgetActionState.value = widgetAction
            widgetTargetIdState.value = intent.getStringExtra(WidgetUpdateHelper.EXTRA_TARGET_DEVICE_ID)
            intent.removeExtra(WidgetUpdateHelper.EXTRA_WIDGET_ACTION)
            intent.removeExtra(WidgetUpdateHelper.EXTRA_TARGET_DEVICE_ID)
        }
    }
}
