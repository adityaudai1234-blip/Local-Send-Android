package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsDataStore
import com.example.data.transfer.StorageManager
import com.example.di.DefaultAppContainer
import com.example.domain.model.AccentColorOption
import com.example.domain.model.AppErrorMapper
import com.example.domain.model.AppErrorReporter
import com.example.domain.model.AppErrorSanitizer
import com.example.domain.model.AppSettings
import com.example.domain.model.ErrorCategory
import com.example.domain.model.PairedDevice
import com.example.domain.model.ReleaseNotesCatalog
import com.example.domain.model.ThemeMode
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.presentation.about.AboutScreen
import com.example.presentation.components.ReleaseNotesDialog
import com.example.presentation.diagnostics.DiagnosticsViewModel
import com.example.presentation.diagnostics.NetworkDiagnosticsScreen
import com.example.presentation.onboarding.OnboardingNextAction
import com.example.presentation.onboarding.OnboardingScreen
import com.example.presentation.pairing.ScanQrDialog
import com.example.presentation.privacy.PrivacyPolicyScreen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.settings.SettingsViewModel
import com.example.ui.theme.LocalDropTheme
import com.example.util.LocalCrashReporter
import com.example.util.PermissionHelper
import com.example.util.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7OnboardingReleaseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        AppErrorReporter.clear()
        LocalCrashReporter.clearLastCrashSummary(context)
        context.getSharedPreferences("localdrop_permission_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        AppErrorReporter.clear()
        LocalCrashReporter.clearLastCrashSummary(context)
    }

    // -------------------------------------------------------------------------
    // 1. Release Versioning, Error Sanitization & Local Crash Diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun buildVersionAndReleaseNotes_areConsistentAcrossBuildConfigSettingsAndCatalog() {
        assertEquals("2.2.0", BuildConfig.VERSION_NAME)
        assertEquals(7, BuildConfig.VERSION_CODE)

        val defaultSettings = AppSettings(deviceName = "Pixel 9")
        assertEquals(BuildConfig.VERSION_NAME, defaultSettings.appVersion)
        assertEquals(BuildConfig.VERSION_CODE.toString(), defaultSettings.buildNumber)

        val latest = ReleaseNotesCatalog.latest
        assertEquals(BuildConfig.VERSION_NAME, latest.version)
        assertEquals(BuildConfig.VERSION_CODE.toString(), latest.buildNumber)
        assertTrue(latest.newFeatures.isNotEmpty())
        assertTrue(latest.improvements.isNotEmpty())
        assertTrue(latest.bugFixes.isNotEmpty())
        assertTrue(latest.securityUpdates.isNotEmpty())
    }

    @Test
    fun appErrorSanitizerAndMapper_redactSecretsAndCategorizeExceptions() {
        val rawLeak = """handshake failed authToken="superSecret123" pairingToken=tok_999 password=letmein Bearer abc.def.ghi hash=0123456789abcdef0123456789abcdef"""
        val redacted = AppErrorSanitizer.redact(rawLeak)

        assertFalse(redacted.contains("superSecret123"))
        assertFalse(redacted.contains("tok_999"))
        assertFalse(redacted.contains("letmein"))
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("0123456789abcdef0123456789abcdef"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("[REDACTED_HEX]"))

        val connErr = AppErrorMapper.fromThrowable(ErrorCategory.CONNECTION, ConnectException("refused"))
        assertEquals(ErrorCategory.CONNECTION, connErr.category)
        assertTrue(connErr.userMessage.contains("same network", ignoreCase = true))

        val timeoutErr = AppErrorMapper.fromThrowable(ErrorCategory.CONNECTION, SocketTimeoutException("read timed out"))
        assertTrue(timeoutErr.userMessage.contains("timed out", ignoreCase = true))

        val hostErr = AppErrorMapper.fromThrowable(ErrorCategory.DISCOVERY, UnknownHostException("peer.local"))
        assertTrue(hostErr.userMessage.contains("address", ignoreCase = true))

        val tlsErr = AppErrorMapper.fromThrowable(ErrorCategory.AUTHENTICATION, SSLException("bad cert"))
        assertTrue(tlsErr.userMessage.contains("TLS", ignoreCase = true))

        val eofErr = AppErrorMapper.fromThrowable(ErrorCategory.PROTOCOL, EOFException("eof"))
        assertTrue(eofErr.userMessage.contains("closed the connection", ignoreCase = true))

        val storageErr = AppErrorMapper.fromThrowable(ErrorCategory.STORAGE, IOException("disk full"))
        assertTrue(storageErr.userMessage.contains("storage space", ignoreCase = true))

        val permErr = AppErrorMapper.fromThrowable(ErrorCategory.PERMISSION, SecurityException("denied"))
        assertTrue(permErr.userMessage.contains("permission", ignoreCase = true))

        AppErrorReporter.report(
            category = ErrorCategory.PAIRING,
            userMessage = "Pairing failed with token=secretValue99",
            technicalDetails = """{"authToken":"topSecretToken"}""",
            targetLabel = "Studio Mac"
        )
        val recorded = AppErrorReporter.errors.value
        assertEquals(1, recorded.size)
        assertFalse(recorded.first().userMessage.contains("secretValue99"))
        assertFalse((recorded.first().technicalDetails ?: "").contains("topSecretToken"))
    }

    @Test
    fun localCrashReporter_persistsRedactedCrashSummaryAndClearsCleanly() {
        val boom = IllegalStateException("Crash with authToken=hiddenSecret42")
        LocalCrashReporter.recordCrash(context, "worker-thread", boom)

        val summary = LocalCrashReporter.readLastCrashSummary(context)
        assertNotNull(summary)
        assertTrue(summary!!.contains("worker-thread"))
        assertTrue(summary.contains("IllegalStateException"))
        assertFalse(summary.contains("hiddenSecret42"))
        assertTrue(summary.contains("[REDACTED]"))

        assertTrue(LocalCrashReporter.clearLastCrashSummary(context))
        assertNull(LocalCrashReporter.readLastCrashSummary(context))
    }

    @Test
    fun permissionHelper_tracksAskedDismissedAndVersionAwareStates() {
        assertTrue(PermissionHelper.isNearbyWifiRequiredOnThisDevice())
        assertTrue(PermissionHelper.isNotificationsPermissionRequiredOnThisDevice())

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            assertEquals(PermissionState.NOT_ASKED, PermissionHelper.getPermissionState(context, Manifest.permission.CAMERA))
            PermissionHelper.markPermissionAsked(context, Manifest.permission.CAMERA)
            assertTrue(PermissionHelper.wasPermissionAsked(context, Manifest.permission.CAMERA))
            assertEquals(PermissionState.RATIONALE_NEEDED, PermissionHelper.getPermissionState(context, Manifest.permission.CAMERA))
        }

        assertFalse(PermissionHelper.isBannerDismissed(context, Manifest.permission.POST_NOTIFICATIONS))
        PermissionHelper.markBannerDismissed(context, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(PermissionHelper.isBannerDismissed(context, Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun settingsDataStore_persistsPhase7PreferencesRoundTrip() {
        runBlocking {
            val dataStore = SettingsDataStore(context)
            dataStore.updateHasCompletedOnboarding(true)
            dataStore.updateLastSeenReleaseVersion("2.2.0")
            dataStore.updateAppLanguage("English (System)")
            dataStore.updateNotifyIncomingRequests(false)
            dataStore.updateNotifyTransferProgress(false)
            dataStore.updateNotifyTransferComplete(true)

            val loaded = dataStore.settingsFlow.first()
            assertTrue(loaded.hasCompletedOnboarding)
            assertEquals("2.2.0", loaded.lastSeenReleaseVersion)
            assertEquals("English (System)", loaded.appLanguage)
            assertFalse(loaded.notifyIncomingRequests)
            assertFalse(loaded.notifyTransferProgress)
            assertTrue(loaded.notifyTransferComplete)
            assertEquals("2.2.0", loaded.appVersion)
            assertEquals("7", loaded.buildNumber)
        }
    }

    // -------------------------------------------------------------------------
    // 2. Onboarding, Privacy, About, What's New, Settings & Error UI Tests
    // -------------------------------------------------------------------------

    @Test
    fun onboardingScreen_walksThroughAllFiveStepsAndCompletesWithSelectedAction() {
        val container = DefaultAppContainer(context)
        val settingsRepo = InMemorySettingsRepository(AppSettings(deviceName = "Pixel Test", hasCompletedOnboarding = false))
        val settingsVm = SettingsViewModel(
            settingsRepository = settingsRepo,
            pairedDeviceRepository = InMemoryPairedDeviceRepository(),
            storageManager = StorageManager(context),
            nsdDiscoveryManager = container.nsdDiscoveryManager
        )

        var finishedAction: OnboardingNextAction? = null
        var openedPrivacy = false

        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    OnboardingScreen(
                        settingsViewModel = settingsVm,
                        onFinishOnboarding = { finishedAction = it },
                        onOpenPrivacyPolicy = { openedPrivacy = true }
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Step 1: Welcome
            composeTestRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("Share files directly between your devices. Fast, private and without cloud uploads.")
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag("onboarding_learn_more_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("How LocalDrop Works").performScrollTo().assertIsDisplayed()

            composeTestRule.onNodeWithTag("onboarding_get_started_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            // Step 2: Privacy & Zero-Cloud
            composeTestRule.onNodeWithText("Privacy by Architecture").assertIsDisplayed()
            composeTestRule.onNodeWithText("Files never leave your local network").assertIsDisplayed()
            composeTestRule.onNodeWithTag("onboarding_view_privacy_policy_button").performScrollTo().performClick()
            assertTrue(openedPrivacy)

            composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
            composeTestRule.waitForIdle()

            // Step 3: Permissions
            composeTestRule.onNodeWithText("Permissions Explained").assertIsDisplayed()
            composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
            composeTestRule.waitForIdle()

            // Step 4: Device Setup
            composeTestRule.onNodeWithTag("onboarding_device_name_input").performTextReplacement("My Studio Phone")
            composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
            testDispatcher.scheduler.advanceUntilIdle()
            composeTestRule.waitForIdle()

            // Step 5: Completion
            composeTestRule.onNodeWithText("You're ready to share files.").assertIsDisplayed()
            composeTestRule.onNodeWithTag("onboarding_action_find_devices").performClick()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(OnboardingNextAction.FIND_DEVICES, finishedAction)
            assertTrue(settingsRepo.settings.value.hasCompletedOnboarding)
            assertEquals("My Studio Phone", settingsRepo.settings.value.deviceName)
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun privacyPolicyScreen_rendersAllNineZeroCloudSections() {
        composeTestRule.setContent {
            LocalDropTheme {
                PrivacyPolicyScreen(onBack = {}, onOpenPrivacyDashboard = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("privacy_policy_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("1. Local-Network Communication").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("9. Data Retention & Deletion"))
        composeTestRule.onNodeWithText("9. Data Retention & Deletion").assertIsDisplayed()
    }

    @Test
    fun aboutScreen_andReleaseNotesDialog_renderVersionAndActions() {
        var policyOpenedFromAbout = false
        var replayClicked = false

        composeTestRule.setContent {
            LocalDropTheme {
                AboutScreen(
                    onBack = {},
                    onOpenPrivacyPolicy = { policyOpenedFromAbout = true },
                    onReplayOnboarding = { replayClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Build ${BuildConfig.VERSION_CODE}").assertIsDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("about_release_notes_row"))
        composeTestRule.onNodeWithTag("about_release_notes_row").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("release_notes_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("New Features").assertIsDisplayed()
        composeTestRule.onNodeWithText("Security Updates").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("release_notes_continue_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("release_notes_dialog").assertDoesNotExist()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("about_privacy_policy_row"))
        composeTestRule.onNodeWithTag("about_privacy_policy_row").performClick()
        assertTrue(policyOpenedFromAbout)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("about_replay_onboarding_row"))
        composeTestRule.onNodeWithTag("about_replay_onboarding_row").performClick()
        assertTrue(replayClicked)
    }

    @Test
    fun settingsScreen_displaysAllSevenSectionsAndGranularNotifications() {
        val container = DefaultAppContainer(context)
        val settingsRepo = InMemorySettingsRepository(AppSettings(deviceName = "Pixel Settings"))
        val settingsVm = SettingsViewModel(
            settingsRepository = settingsRepo,
            pairedDeviceRepository = InMemoryPairedDeviceRepository(),
            storageManager = StorageManager(context),
            nsdDiscoveryManager = container.nsdDiscoveryManager
        )

        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    SettingsScreen(viewModel = settingsVm)
                }
            }
            composeTestRule.waitForIdle()

            val expectedSections = listOf(
                "General",
                "Discovery",
                "Transfers",
                "Notifications",
                "Privacy & Security",
                "Storage",
                "About"
            )
            expectedSections.forEach { section ->
                composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(section))
                composeTestRule.onNodeWithText(section).assertIsDisplayed()
            }

            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("switch_notify_incoming"))
            composeTestRule.onNodeWithTag("switch_notify_incoming").assertIsDisplayed()
            composeTestRule.onNodeWithTag("switch_notify_progress").assertIsDisplayed()
            composeTestRule.onNodeWithTag("switch_notify_complete").assertIsDisplayed()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun networkDiagnosticsScreen_rendersCategorizedErrorAndLocalCrashSummary() {
        val container = DefaultAppContainer(context)
        LocalCrashReporter.recordCrash(context, "main", RuntimeException("Sample crash token=secretVal"))
        AppErrorReporter.report(
            category = ErrorCategory.TRANSFER,
            userMessage = "Transfer failed on peer",
            technicalDetails = "SocketTimeoutException: read timed out",
            targetLabel = "Linux Desktop",
            address = "192.168.1.77:53317"
        )
        val diagVm = DiagnosticsViewModel(
            context = context,
            discoveryRepository = container.deviceDiscoveryRepository,
            connectionManager = container.connectionManager,
            pairedDeviceRepository = container.pairedDeviceRepository,
            tcpServer = container.tcpServer,
            settingsRepository = container.settingsRepository
        )

        try {
            composeTestRule.setContent {
                LocalDropTheme {
                    NetworkDiagnosticsScreen(viewModel = diagVm, onBack = {})
                }
            }
            val deadline = System.currentTimeMillis() + 5_000L
            while (diagVm.lastCrashSummary.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L)
                testDispatcher.scheduler.advanceUntilIdle()
            }
            composeTestRule.waitForIdle()

            val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            composeTestRule.onNode(verticalList).performScrollToNode(hasTestTag("last_crash_summary_card"))
            composeTestRule.onNodeWithTag("last_crash_summary_card").assertIsDisplayed()

            composeTestRule.onNode(verticalList).performScrollToNode(hasTestTag("error_card_transfer"))
            composeTestRule.onNodeWithTag("error_card_transfer").assertIsDisplayed()
            composeTestRule.onNodeWithText("Transfer failed on peer").assertIsDisplayed()
        } finally {
            container.nsdDiscoveryManager.cleanup()
            container.connectionManager.cleanup()
            container.tcpServer.stop()
        }
    }

    @Test
    fun scanQrDialog_withoutCameraPermissionShowsContextualRationaleWithoutAutoPrompt() {
        composeTestRule.setContent {
            LocalDropTheme {
                ScanQrDialog(
                    onPayloadScanned = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scan_qr_dialog").assertIsDisplayed()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            composeTestRule.onNodeWithTag("camera_enable_permission_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("camera_switch_manual_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("manual_qr_input").assertIsDisplayed()
        }
    }

    @Test
    fun releaseNotesDialog_rendersSectionsDirectly() {
        var dismissed = false
        composeTestRule.setContent {
            LocalDropTheme {
                ReleaseNotesDialog(onDismiss = { dismissed = true })
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("release_notes_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("release_notes_continue_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(dismissed)
    }

    private class InMemorySettingsRepository(initial: AppSettings) : SettingsRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
        override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun setDeviceName(name: String) { _settings.value = _settings.value.copy(deviceName = name) }
        override suspend fun setThemeMode(mode: ThemeMode) { _settings.value = _settings.value.copy(themeMode = mode) }
        override suspend fun setAccentColor(option: AccentColorOption) { _settings.value = _settings.value.copy(accentColor = option) }
        override suspend fun setAppLanguage(language: String) { _settings.value = _settings.value.copy(appLanguage = language) }
        override suspend fun setHasCompletedOnboarding(completed: Boolean) { _settings.value = _settings.value.copy(hasCompletedOnboarding = completed) }
        override suspend fun setLastSeenReleaseVersion(version: String) { _settings.value = _settings.value.copy(lastSeenReleaseVersion = version) }
        override suspend fun setAutoStartReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(autoStartReceiving = enabled) }
        override suspend fun setAutoAcceptTrusted(enabled: Boolean) { _settings.value = _settings.value.copy(autoAcceptTrusted = enabled) }
        override suspend fun setAskBeforeReceiving(enabled: Boolean) { _settings.value = _settings.value.copy(askBeforeReceiving = enabled) }
        override suspend fun setDownloadLocation(path: String, treeUri: String?) {
            _settings.value = _settings.value.copy(downloadLocation = path, customDownloadTreeUri = treeUri)
        }
        override suspend fun setPort(port: Int) { _settings.value = _settings.value.copy(port = port) }
        override suspend fun setBroadcastEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(broadcastEnabled = enabled) }
        override suspend fun setShowNotifications(enabled: Boolean) { _settings.value = _settings.value.copy(showNotifications = enabled) }
        override suspend fun setNotifyIncomingRequests(enabled: Boolean) { _settings.value = _settings.value.copy(notifyIncomingRequests = enabled) }
        override suspend fun setNotifyTransferProgress(enabled: Boolean) { _settings.value = _settings.value.copy(notifyTransferProgress = enabled) }
        override suspend fun setNotifyTransferComplete(enabled: Boolean) { _settings.value = _settings.value.copy(notifyTransferComplete = enabled) }
        override suspend fun setDiscoveryIntervalSeconds(seconds: Int) { _settings.value = _settings.value.copy(discoveryIntervalSeconds = seconds) }
        override suspend fun setConnectionTimeoutMs(timeoutMs: Int) { _settings.value = _settings.value.copy(connectionTimeoutMs = timeoutMs) }
        override suspend fun setMaxConcurrent(count: Int) { _settings.value = _settings.value.copy(maxConcurrentTransfers = count) }
        override suspend fun setTransferBufferSizeKb(sizeKb: Int) { _settings.value = _settings.value.copy(transferBufferSizeKb = sizeKb) }
        override suspend fun setDuplicateHandling(handling: String) { _settings.value = _settings.value.copy(duplicateHandling = handling) }
        override suspend fun setPreserveOriginalNames(enabled: Boolean) { _settings.value = _settings.value.copy(preserveOriginalNames = enabled) }
        override suspend fun setAutoOpenCompleted(enabled: Boolean) { _settings.value = _settings.value.copy(autoOpenCompleted = enabled) }
        override suspend fun setMaxAcceptedFileSizeMb(sizeMb: Long) { _settings.value = _settings.value.copy(maxAcceptedFileSizeMb = sizeMb) }
        override suspend fun setRequireApprovalUnknown(enabled: Boolean) { _settings.value = _settings.value.copy(requireApprovalUnknown = enabled) }
        override suspend fun setTlsPreferred(enabled: Boolean) { _settings.value = _settings.value.copy(tlsPreferred = enabled) }
        override suspend fun setHapticsEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(hapticsEnabled = enabled) }
        override suspend fun setSuggestRecentDevices(enabled: Boolean) { _settings.value = _settings.value.copy(suggestRecentDevices = enabled) }
        override suspend fun setRememberLastDestination(enabled: Boolean) { _settings.value = _settings.value.copy(rememberLastDestination = enabled) }
        override suspend fun setLastDestinationDeviceId(deviceId: String?) { _settings.value = _settings.value.copy(lastDestinationDeviceId = deviceId) }
        override suspend fun setOrganizeReceivedByType(enabled: Boolean) { _settings.value = _settings.value.copy(organizeReceivedByType = enabled) }
        override suspend fun setAutoCleanTempFiles(enabled: Boolean) { _settings.value = _settings.value.copy(autoCleanTempFiles = enabled) }
    }

    private class InMemoryPairedDeviceRepository : PairedDeviceRepository {
        private val devices = MutableStateFlow<List<PairedDevice>>(emptyList())
        override val pairedDevices: Flow<List<PairedDevice>> = devices.asStateFlow()
        override suspend fun getPairedDevice(id: String) = devices.value.find { it.id == id }
        override suspend fun savePairedDevice(device: PairedDevice, rawAuthToken: String) {}
        override suspend fun getAuthToken(deviceId: String): String? = null
        override suspend fun removePairedDevice(id: String) {}
        override suspend fun renameDevice(id: String, newAlias: String) {}
        override suspend fun updateLastConnected(id: String) {}
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setAutoAccept(id: String, autoAccept: Boolean) {}
        override suspend fun incrementUsage(id: String) {}
        override suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int) {}
        override suspend fun clearAllDevices() {}
    }
}
