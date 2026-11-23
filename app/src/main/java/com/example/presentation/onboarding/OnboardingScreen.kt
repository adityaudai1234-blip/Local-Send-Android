package com.example.presentation.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.presentation.components.ContextualPermissionCard
import com.example.presentation.components.LocalDropBrandMark
import com.example.presentation.components.StatusBadge
import com.example.presentation.settings.SettingsViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealPrimary
import com.example.util.PermissionHelper

enum class OnboardingStep(val title: String) {
    WELCOME("Welcome"),
    PRIVACY("Privacy & Zero-Cloud"),
    PERMISSIONS("Permissions"),
    DEVICE_SETUP("Device Setup"),
    COMPLETION("Ready")
}

enum class OnboardingNextAction {
    HOME,
    FIND_DEVICES,
    SEND_FILES,
    RECEIVE_FILES
}

@Composable
fun OnboardingScreen(
    settingsViewModel: SettingsViewModel,
    onFinishOnboarding: (OnboardingNextAction) -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val steps = OnboardingStep.entries
    val currentStep = steps[currentStepIndex]

    var deviceNameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var broadcastDraft by remember(settings.broadcastEnabled) { mutableStateOf(settings.broadcastEnabled) }
    var askBeforeReceivingDraft by remember(settings.askBeforeReceiving) { mutableStateOf(settings.askBeforeReceiving) }
    var showPrivacyDetails by remember { mutableStateOf(false) }

    // Trigger recomposition when permissions change
    var permissionRefreshNonce by remember { mutableIntStateOf(0) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        PermissionHelper.markPermissionAsked(context, Manifest.permission.CAMERA)
        permissionRefreshNonce++
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHelper.markPermissionAsked(context, Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionRefreshNonce++
    }

    val nearbyWifiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHelper.markPermissionAsked(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionRefreshNonce++
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                uri.lastPathSegment ?: "Selected Folder"
            }
            val friendlyName = docId.substringAfterLast(':').ifBlank { docId }
            settingsViewModel.updateDownloadLocation("Custom: $friendlyName", uri.toString())
        }
    }

    BackHandler(enabled = currentStepIndex > 0) {
        currentStepIndex = (currentStepIndex - 1).coerceAtLeast(0)
    }

    fun saveDeviceSetupDrafts() {
        val trimmed = deviceNameDraft.trim()
        if (trimmed.isNotBlank()) {
            settingsViewModel.updateDeviceName(trimmed)
        }
        settingsViewModel.updateBroadcastEnabled(broadcastDraft)
        settingsViewModel.updateAskBeforeReceiving(askBeforeReceivingDraft)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // Top step bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Step ${currentStepIndex + 1} of ${steps.size} • ${currentStep.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (currentStepIndex < steps.lastIndex) {
                    TextButton(
                        onClick = {
                            saveDeviceSetupDrafts()
                            settingsViewModel.updateHasCompletedOnboarding(true)
                            onFinishOnboarding(OnboardingNextAction.HOME)
                        },
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text("Skip")
                    }
                }
            }

            LinearProgressIndicator(
                progress = { (currentStepIndex + 1).toFloat() / steps.size.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentStep,
                label = "onboarding_step",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { step ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (step) {
                        OnboardingStep.WELCOME -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                LocalDropBrandMark(size = 64.dp, cornerRadius = 18.dp)
                                Text(
                                    text = "LocalDrop",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Share files directly between your devices. Fast, private and without cloud uploads.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Image(
                                    painter = painterResource(id = R.drawable.img_onboarding_share_1795360246757),
                                    contentDescription = "Direct device-to-device local Wi-Fi file sharing illustration",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                )

                                if (showPrivacyDetails) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "How LocalDrop Works",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "• Discovers nearby Android and Tauri desktop peers using local mDNS (_lsend._tcp).\n• Pairs cleanly with QR codes and mutual tokens stored in Android Keystore.\n• Streams files directly over LAN TCP/TLS sockets with SHA-256 verification.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showPrivacyDetails = !showPrivacyDetails },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("onboarding_learn_more_button"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(if (showPrivacyDetails) "Hide Details" else "Learn More")
                                    }
                                    Button(
                                        onClick = { currentStepIndex = OnboardingStep.PRIVACY.ordinal },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("onboarding_get_started_button"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Get Started")
                                    }
                                }
                            }
                        }

                        OnboardingStep.PRIVACY -> {
                            Text(
                                text = "Privacy by Architecture",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "LocalDrop never uploads your files, contacts, or diagnostics to external servers.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            PrivacyHighlightCard(
                                icon = Icons.Default.CloudOff,
                                title = "Files never leave your local network",
                                description = "Transfers flow peer-to-peer over your Wi-Fi or local router directly between devices."
                            )
                            PrivacyHighlightCard(
                                icon = Icons.Default.Lock,
                                title = "No cloud servers, accounts, or tracking",
                                description = "No login, no remote analytics, no crash-upload SDKs, and zero background telemetry."
                            )
                            PrivacyHighlightCard(
                                icon = Icons.Default.QrCodeScanner,
                                title = "Mutual QR pairing & explicit approval",
                                description = "Pair securely with desktop and mobile peers and confirm incoming transfers before saving."
                            )

                            TextButton(
                                onClick = onOpenPrivacyPolicy,
                                modifier = Modifier.testTag("onboarding_view_privacy_policy_button")
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Read Full Privacy Information")
                            }
                        }

                        OnboardingStep.PERMISSIONS -> {
                            // Re-read states cleanly on refreshNonce updates
                            val refreshKey = permissionRefreshNonce
                            val nearbyState = remember(refreshKey) {
                                PermissionHelper.getPermissionState(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                            }
                            val cameraState = remember(refreshKey) {
                                PermissionHelper.getPermissionState(context, Manifest.permission.CAMERA)
                            }
                            val notificationsState = remember(refreshKey) {
                                PermissionHelper.getPermissionState(context, Manifest.permission.POST_NOTIFICATIONS)
                            }

                            Text(
                                text = "Permissions Explained",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Every permission is optional until you use its feature. You stay in full control.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (PermissionHelper.isNearbyWifiRequiredOnThisDevice()) {
                                ContextualPermissionCard(
                                    title = "Nearby Wi-Fi Devices",
                                    rationale = "Used on Android 13+ to help discover compatible peers on your local Wi-Fi network.",
                                    permissionState = nearbyState,
                                    icon = Icons.Default.Wifi,
                                    onRequestPermission = {
                                        nearbyWifiLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                                    },
                                    onOpenSettings = { PermissionHelper.openAppSettings(context) }
                                )
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = StatusSuccess.copy(alpha = 0.08f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Wifi, contentDescription = null, tint = StatusSuccess)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Local Wi-Fi Discovery Ready", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "This Android version allows local mDNS discovery without extra runtime Wi-Fi permission.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            ContextualPermissionCard(
                                title = "Camera (QR Code Scanning)",
                                rationale = "Used only when scanning a pairing QR code from a desktop or mobile device. Manual JSON pairing works without camera access.",
                                permissionState = cameraState,
                                icon = Icons.Default.CameraAlt,
                                onRequestPermission = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                                onOpenSettings = { PermissionHelper.openAppSettings(context) }
                            )

                            if (PermissionHelper.isNotificationsPermissionRequiredOnThisDevice()) {
                                ContextualPermissionCard(
                                    title = "Transfer Notifications",
                                    rationale = "Alerts you about incoming file requests, live transfer progress, and completed transfers.",
                                    permissionState = notificationsState,
                                    icon = Icons.Default.Notifications,
                                    onRequestPermission = {
                                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    onOpenSettings = { PermissionHelper.openAppSettings(context) }
                                )
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TealPrimary)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Zero Broad Storage Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Uses Android's privacy-safe file & folder pickers (SAF) only for files and folders you explicitly choose.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        OnboardingStep.DEVICE_SETUP -> {
                            Text(
                                text = "Configure This Device",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Customize how nearby devices see you and where received files are saved.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = deviceNameDraft,
                                onValueChange = { deviceNameDraft = it },
                                label = { Text("Device Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("onboarding_device_name_input")
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Download Folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                settings.downloadLocation,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        StatusBadge(
                                            text = if (settings.customDownloadTreeUri != null) "Custom" else "Default",
                                            color = CyanAccent,
                                            showDot = false
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { folderPickerLauncher.launch(null) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("onboarding_choose_folder_button"),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Choose Custom Save Folder")
                                    }
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Broadcast on Local Network", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Allow peers on the same Wi-Fi to discover this device automatically.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = broadcastDraft,
                                            onCheckedChange = { broadcastDraft = it },
                                            modifier = Modifier.testTag("onboarding_broadcast_switch")
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Require Transfer Approval", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Prompt before saving files from devices unless explicitly trusted.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = askBeforeReceivingDraft,
                                            onCheckedChange = { askBeforeReceivingDraft = it },
                                            modifier = Modifier.testTag("onboarding_approval_switch")
                                        )
                                    }
                                }
                            }
                        }

                        OnboardingStep.COMPLETION -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = "You're ready to share files.",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Device Name: ${deviceNameDraft.ifBlank { settings.deviceName }} • Local Port ${settings.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Button(
                                    onClick = {
                                        saveDeviceSetupDrafts()
                                        settingsViewModel.updateHasCompletedOnboarding(true)
                                        onFinishOnboarding(OnboardingNextAction.FIND_DEVICES)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_action_find_devices"),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Find Nearby Devices")
                                }

                                FilledTonalButton(
                                    onClick = {
                                        saveDeviceSetupDrafts()
                                        settingsViewModel.updateHasCompletedOnboarding(true)
                                        onFinishOnboarding(OnboardingNextAction.SEND_FILES)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_action_send_files"),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Send Files")
                                }

                                OutlinedButton(
                                    onClick = {
                                        saveDeviceSetupDrafts()
                                        settingsViewModel.updateHasCompletedOnboarding(true)
                                        onFinishOnboarding(OnboardingNextAction.RECEIVE_FILES)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_action_receive_files"),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Receive Files")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer back / next navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStepIndex > 0) {
                    OutlinedButton(
                        onClick = { currentStepIndex = (currentStepIndex - 1).coerceAtLeast(0) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("onboarding_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (currentStep != OnboardingStep.WELCOME && currentStep != OnboardingStep.COMPLETION) {
                    Button(
                        onClick = {
                            if (currentStep == OnboardingStep.DEVICE_SETUP) {
                                saveDeviceSetupDrafts()
                            }
                            currentStepIndex = (currentStepIndex + 1).coerceAtMost(steps.lastIndex)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("onboarding_next_button")
                    ) {
                        Text(if (currentStep == OnboardingStep.DEVICE_SETUP) "Finish Setup" else "Continue")
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else if (currentStep == OnboardingStep.COMPLETION) {
                    TextButton(
                        onClick = {
                            saveDeviceSetupDrafts()
                            settingsViewModel.updateHasCompletedOnboarding(true)
                            onFinishOnboarding(OnboardingNextAction.HOME)
                        },
                        modifier = Modifier.testTag("onboarding_open_home_button")
                    ) {
                        Text("Go to Home")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyHighlightCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
