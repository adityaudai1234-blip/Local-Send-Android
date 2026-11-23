package com.example.presentation.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.BuildConfig
import com.example.LocalDropApp
import com.example.domain.model.Device
import com.example.presentation.ViewModelFactory
import com.example.presentation.about.AboutScreen
import com.example.presentation.components.ReleaseNotesDialog
import com.example.presentation.devices.DevicesScreen
import com.example.presentation.devices.DevicesViewModel
import com.example.presentation.diagnostics.DiagnosticsViewModel
import com.example.presentation.diagnostics.NetworkDiagnosticsScreen
import com.example.presentation.history.TransferDetailsScreen
import com.example.presentation.home.HomeScreen
import com.example.presentation.home.HomeViewModel
import com.example.presentation.onboarding.OnboardingNextAction
import com.example.presentation.onboarding.OnboardingScreen
import com.example.presentation.privacy.PrivacyDashboardScreen
import com.example.presentation.privacy.PrivacyPolicyScreen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.settings.SettingsViewModel
import com.example.presentation.storage.ReceivedFilesScreen
import com.example.presentation.storage.StorageManagementScreen
import com.example.presentation.storage.StorageViewModel
import com.example.presentation.transfers.TransfersScreen
import com.example.presentation.transfers.TransfersViewModel
import com.example.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MainAppScaffold(
    app: LocalDropApp,
    sharedUris: List<Uri> = emptyList(),
    onSharedUrisConsumed: () -> Unit = {},
    widgetAction: String? = null,
    widgetTargetDeviceId: String? = null,
    onWidgetActionConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val viewModelFactory = ViewModelFactory(app.container, app.applicationContext)

    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory)
    val devicesViewModel: DevicesViewModel = viewModel(factory = viewModelFactory)
    val transfersViewModel: TransfersViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val storageViewModel: StorageViewModel = viewModel(factory = viewModelFactory)
    val diagnosticsViewModel: DiagnosticsViewModel = viewModel(factory = viewModelFactory)
    val coroutineScope = rememberCoroutineScope()

    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by app.container.settingsRepository.isLoaded.collectAsState()

    var initialOnboardingChecked by remember { mutableStateOf(false) }
    LaunchedEffect(settingsLoaded, settings.hasCompletedOnboarding, sharedUris, widgetAction) {
        if (settingsLoaded && !initialOnboardingChecked) {
            initialOnboardingChecked = true
            if (!settings.hasCompletedOnboarding && sharedUris.isEmpty() && widgetAction.isNullOrBlank()) {
                navController.navigate(Screen.ROUTE_ONBOARDING) {
                    launchSingleTop = true
                }
            }
        }
    }

    var releaseNotesDismissedInSession by remember { mutableStateOf(false) }
    val shouldShowWhatsNew = settingsLoaded &&
        settings.hasCompletedOnboarding &&
        !releaseNotesDismissedInSession &&
        currentRoute != Screen.ROUTE_ONBOARDING &&
        settings.lastSeenReleaseVersion != BuildConfig.VERSION_NAME

    var widgetTargetPending by remember { mutableStateOf<Device?>(null) }
    val widgetFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            homeViewModel.onFilesPicked(uris, context, widgetTargetPending)
        }
        widgetTargetPending = null
    }

    // Handle Android Share Sheet (ACTION_SEND / ACTION_SEND_MULTIPLE)
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            homeViewModel.onSharedFilesReceived(sharedUris, context)
            onSharedUrisConsumed()
        }
    }

    // Handle Home Screen Glance Widget actions
    LaunchedEffect(widgetAction, widgetTargetDeviceId) {
        if (!widgetAction.isNullOrBlank()) {
            when (widgetAction) {
                WidgetUpdateHelper.ACTION_VIEW_DEVICES -> {
                    navController.navigate(Screen.Devices.route) { launchSingleTop = true }
                }
                WidgetUpdateHelper.ACTION_VIEW_TRANSFERS -> {
                    navController.navigate(Screen.Transfers.route) { launchSingleTop = true }
                }
                WidgetUpdateHelper.ACTION_OPEN_PICKER -> {
                    navController.navigate(Screen.Home.route) { launchSingleTop = true }
                    widgetTargetPending = null
                    widgetFilePicker.launch(arrayOf("*/*"))
                }
                WidgetUpdateHelper.ACTION_QUICK_SEND -> {
                    navController.navigate(Screen.Home.route) { launchSingleTop = true }
                    widgetTargetPending = homeViewModel.resolveQuickSendTarget(widgetTargetDeviceId)
                    widgetFilePicker.launch(arrayOf("*/*"))
                }
            }
            onWidgetActionConsumed()
        }
    }

    val isTopLevelRoute = Screen.bottomNavItems.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (isTopLevelRoute) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.testTag("bottom_navigation_bar")
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) screen.filledIcon else screen.outlinedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("nav_item_${screen.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    enterTransition = { fadeIn(animationSpec = tween(220)) },
                    exitTransition = { fadeOut(animationSpec = tween(220)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                    popExitTransition = { fadeOut(animationSpec = tween(220)) }
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateToDevices = {
                                navController.navigate(Screen.Devices.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToTransfers = {
                                navController.navigate(Screen.Transfers.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToReceivedFiles = {
                                navController.navigate(Screen.ROUTE_RECEIVED_FILES)
                            },
                            onNavigateToDiagnostics = {
                                navController.navigate(Screen.ROUTE_NETWORK_DIAGNOSTICS)
                            },
                            onNavigateToPrivacyDashboard = {
                                navController.navigate(Screen.ROUTE_PRIVACY_DASHBOARD)
                            }
                        )
                    }

                    composable(Screen.Devices.route) {
                        DevicesScreen(
                            viewModel = devicesViewModel,
                            onDeviceSelected = {
                                navController.navigate(Screen.Home.route)
                            },
                            onManageTrustedClick = {
                                navController.navigate(Screen.ROUTE_TRUSTED_DEVICES)
                            }
                        )
                    }

                    composable(Screen.Transfers.route) {
                        TransfersScreen(
                            viewModel = transfersViewModel,
                            onOpenTransferDetails = { transferId ->
                                navController.navigate(Screen.transferDetailsRoute(transferId))
                            }
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateToTrustedDevices = {
                                navController.navigate(Screen.ROUTE_TRUSTED_DEVICES)
                            },
                            onNavigateToStorageManagement = {
                                navController.navigate(Screen.ROUTE_STORAGE_MANAGEMENT)
                            },
                            onNavigateToReceivedFiles = {
                                navController.navigate(Screen.ROUTE_RECEIVED_FILES)
                            },
                            onNavigateToDiagnostics = {
                                navController.navigate(Screen.ROUTE_NETWORK_DIAGNOSTICS)
                            },
                            onNavigateToPrivacyDashboard = {
                                navController.navigate(Screen.ROUTE_PRIVACY_DASHBOARD)
                            },
                            onNavigateToPrivacyPolicy = {
                                navController.navigate(Screen.ROUTE_PRIVACY_POLICY)
                            },
                            onNavigateToAbout = {
                                navController.navigate(Screen.ROUTE_ABOUT)
                            },
                            onNavigateToOnboarding = {
                                navController.navigate(Screen.ROUTE_ONBOARDING)
                            }
                        )
                    }

                    composable(Screen.ROUTE_ONBOARDING) {
                        OnboardingScreen(
                            settingsViewModel = settingsViewModel,
                            onOpenPrivacyPolicy = {
                                navController.navigate(Screen.ROUTE_PRIVACY_POLICY)
                            },
                            onFinishOnboarding = { action ->
                                // Mark current release version seen when completing fresh onboarding
                                settingsViewModel.updateLastSeenReleaseVersion(BuildConfig.VERSION_NAME)
                                when (action) {
                                    OnboardingNextAction.FIND_DEVICES -> {
                                        navController.navigate(Screen.Devices.route) {
                                            popUpTo(Screen.Home.route) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                    OnboardingNextAction.SEND_FILES -> {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Home.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                        widgetTargetPending = null
                                        widgetFilePicker.launch(arrayOf("*/*"))
                                    }
                                    OnboardingNextAction.RECEIVE_FILES -> {
                                        if (!homeViewModel.isReceivingActive.value) {
                                            homeViewModel.toggleReceiving()
                                        }
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Home.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                    OnboardingNextAction.HOME -> {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Home.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        )
                    }

                    composable(Screen.ROUTE_PRIVACY_POLICY) {
                        PrivacyPolicyScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPrivacyDashboard = {
                                navController.navigate(Screen.ROUTE_PRIVACY_DASHBOARD)
                            }
                        )
                    }

                    composable(Screen.ROUTE_ABOUT) {
                        AboutScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPrivacyPolicy = {
                                navController.navigate(Screen.ROUTE_PRIVACY_POLICY)
                            },
                            onReplayOnboarding = {
                                navController.navigate(Screen.ROUTE_ONBOARDING)
                            }
                        )
                    }

                    composable(
                        route = Screen.ROUTE_TRANSFER_DETAILS,
                        arguments = listOf(navArgument("transferId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val transferId = backStackEntry.arguments?.getString("transferId").orEmpty()
                        TransferDetailsScreen(
                            transferId = transferId,
                            viewModel = transfersViewModel,
                            onBack = { navController.popBackStack() },
                            onViewTrustedDevices = {
                                navController.navigate(Screen.ROUTE_TRUSTED_DEVICES)
                            }
                        )
                    }

                    composable(Screen.ROUTE_TRUSTED_DEVICES) {
                        com.example.presentation.devices.TrustedDevicesScreen(
                            viewModel = devicesViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.ROUTE_STORAGE_MANAGEMENT) {
                        StorageManagementScreen(
                            viewModel = storageViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.ROUTE_RECEIVED_FILES) {
                        ReceivedFilesScreen(
                            storageManager = app.container.storageManager,
                            onBack = { navController.popBackStack() },
                            onFileDeleted = { deletedPath ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    app.container.appDatabase.transferDao().clearFilePathForPath(deletedPath)
                                }
                            }
                        )
                    }

                    composable(Screen.ROUTE_NETWORK_DIAGNOSTICS) {
                        NetworkDiagnosticsScreen(
                            viewModel = diagnosticsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.ROUTE_PRIVACY_DASHBOARD) {
                        PrivacyDashboardScreen(
                            settingsRepository = app.container.settingsRepository,
                            pairedDeviceRepository = app.container.pairedDeviceRepository,
                            connectionManager = app.container.connectionManager,
                            transferManager = app.container.transferManager,
                            storageManager = app.container.storageManager,
                            nsdDiscoveryManager = app.container.nsdDiscoveryManager,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    if (shouldShowWhatsNew) {
        ReleaseNotesDialog(
            onDismiss = {
                releaseNotesDismissedInSession = true
                settingsViewModel.updateLastSeenReleaseVersion(BuildConfig.VERSION_NAME)
            }
        )
    }
}
