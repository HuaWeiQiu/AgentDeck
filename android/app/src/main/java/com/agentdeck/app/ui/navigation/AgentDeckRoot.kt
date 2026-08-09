package com.agentdeck.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.ui.chat.ChatScreen
import com.agentdeck.app.ui.sessions.SessionsScreen
import com.agentdeck.app.ui.settings.SettingsScreen
import com.agentdeck.app.ui.models.ModelsScreen
import com.agentdeck.app.ui.store.SetupScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("sessions", "对话", Icons.AutoMirrored.Filled.Chat),
    Tab("settings", "设置", Icons.Filled.Settings),
)

internal val standardTopLevelRoutes: Set<String> = tabs.mapTo(linkedSetOf()) { it.route }

@Composable
fun AgentDeckRoot() {
    val navController = rememberNavController()
    val startDestination = remember {
        resolveStartDestination(
            runtimeReady = ServiceLocator.runtime.status().ready,
            setupPreviouslyCompleted = !ServiceLocator.onboarding.shouldOpenDoctor(),
        )
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ServiceLocator.setup.scan()
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in standardTopLevelRoutes) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigateTopLevel(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable("sessions") {
                SessionsScreen(
                    onOpenSetup = { navController.navigate("setup") { launchSingleTop = true } },
                    onOpenChat = { cardId -> navController.navigate("chat/$cardId") },
                )
            }
            composable("setup") {
                val canNavigateBack = navController.previousBackStackEntry != null
                SetupScreen(
                    onBack = if (canNavigateBack) {
                        { navController.navigateUp() }
                    } else {
                        null
                    },
                    onReady = { navigateTopLevel("sessions") },
                    onOpenModels = { navController.navigate("models") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onOpenSetup = { navController.navigate("setup") { launchSingleTop = true } },
                    onOpenModels = { navController.navigate("models") },
                )
            }
            composable("models") {
                ModelsScreen(onBack = navController::navigateUp)
            }
            composable("chat/{cardId}") { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                ChatScreen(
                    cardId = cardId,
                    onBack = navController::navigateUp,
                )
            }
        }
    }
}

internal fun resolveStartDestination(
    runtimeReady: Boolean,
    setupPreviouslyCompleted: Boolean,
): String = if (runtimeReady && setupPreviouslyCompleted) {
    "sessions"
} else {
    "setup"
}
