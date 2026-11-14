package com.example.presentation.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.LocalDropApp
import com.example.presentation.ViewModelFactory
import com.example.presentation.devices.DevicesScreen
import com.example.presentation.devices.DevicesViewModel
import com.example.presentation.devices.TrustedDevicesScreen
import com.example.presentation.history.TransferDetailsScreen
import com.example.presentation.home.HomeScreen
import com.example.presentation.home.HomeViewModel
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.settings.SettingsViewModel
import com.example.presentation.storage.StorageManagementScreen
import com.example.presentation.storage.StorageViewModel
import com.example.presentation.transfers.TransfersScreen
import com.example.presentation.transfers.TransfersViewModel

@Composable
fun MainAppScaffold(
    app: LocalDropApp,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val viewModelFactory = ViewModelFactory(app.container)

    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory)
    val devicesViewModel: DevicesViewModel = viewModel(factory = viewModelFactory)
    val transfersViewModel: TransfersViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val storageViewModel: StorageViewModel = viewModel(factory = viewModelFactory)

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
                        TrustedDevicesScreen(
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
                }
            }
        }
    }
}
