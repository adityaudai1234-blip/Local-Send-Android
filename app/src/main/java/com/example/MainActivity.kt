package com.example

import android.Manifest
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.presentation.navigation.MainAppScaffold
import com.example.ui.theme.LocalDropTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

            LocalDropTheme(themeMode = settings.themeMode) {
                MainAppScaffold(app = app)
            }
        }
    }
}
