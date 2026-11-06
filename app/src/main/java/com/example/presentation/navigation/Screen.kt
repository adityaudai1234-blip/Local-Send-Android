package com.example.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector
) {
    data object Home : Screen(
        route = "home",
        title = "Home",
        filledIcon = Icons.Filled.Home,
        outlinedIcon = Icons.Outlined.Home
    )

    data object Devices : Screen(
        route = "devices",
        title = "Devices",
        filledIcon = Icons.Filled.Devices,
        outlinedIcon = Icons.Outlined.Devices
    )

    data object Transfers : Screen(
        route = "transfers",
        title = "Transfers",
        filledIcon = Icons.Filled.SwapHoriz,
        outlinedIcon = Icons.Outlined.SwapHoriz
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        filledIcon = Icons.Filled.Settings,
        outlinedIcon = Icons.Outlined.Settings
    )

    companion object {
        val bottomNavItems = listOf(Home, Devices, Transfers, Settings)
    }
}
