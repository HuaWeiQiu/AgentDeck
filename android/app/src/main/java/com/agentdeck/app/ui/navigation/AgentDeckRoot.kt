package com.agentdeck.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentdeck.app.ui.models.ModelsScreen
import com.agentdeck.app.ui.sessions.SessionsScreen
import com.agentdeck.app.ui.settings.SettingsScreen
import com.agentdeck.app.ui.store.StoreScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("sessions", "会话", Icons.Filled.Home),
    Tab("store", "商店", Icons.Filled.Extension),
    Tab("models", "模型", Icons.Filled.SmartToy),
    Tab("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun AgentDeckRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "sessions",
            modifier = Modifier.padding(padding),
        ) {
            composable("sessions") { SessionsScreen() }
            composable("store") { StoreScreen() }
            composable("models") { ModelsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
