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
import androidx.compose.runtime.LaunchedEffect
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
import com.agentdeck.app.ui.config.CodexConfigScreen
import com.agentdeck.app.ui.sessions.SessionsScreen
import com.agentdeck.app.ui.settings.SettingsScreen
import com.agentdeck.app.ui.settings.BackupRestoreScreen
import com.agentdeck.app.ui.settings.ConversationDefaultsScreen
import com.agentdeck.app.ui.settings.LabScreenAgentScreen
import com.agentdeck.app.ui.settings.RuntimeEnvironmentScreen
import com.agentdeck.app.ui.models.ModelsScreen
import com.agentdeck.app.ui.extensions.ExtensionDetailScreen
import com.agentdeck.app.ui.extensions.ExtensionsScreen
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
fun AgentDeckRoot(deepLink: Pair<String, Long>? = null) {
    val navController = rememberNavController()
    val startDestination = remember {
        resolveStartDestination(
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

    // Chat event notifications deep-link straight back into the conversation. The key
    // includes the arrival timestamp, so re-tapping a card's notification navigates
    // again even for the same card.
    LaunchedEffect(deepLink) {
        deepLink?.first?.let { cardId ->
            navController.navigate("chat/$cardId") { launchSingleTop = true }
        }
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
                    onOpenModels = { navController.navigate("models") { launchSingleTop = true } },
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
                    onReady = {
                        // setup 作为 startDestination 时必须 inclusive 弹出，
                        // 否则它留在栈底，sessions 页按返回会回到 setup
                        navController.navigate("sessions") {
                            popUpTo("setup") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenModels = { navController.navigate("models") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onOpenSetup = { navController.navigate("setup") { launchSingleTop = true } },
                    onOpenRuntimes = { navController.navigate("runtimes") },
                    onOpenModels = { navController.navigate("models") },
                    onOpenExtensions = { navController.navigate("extensions") },
                    onOpenCodexConfig = { navController.navigate("codex-config") },
                    onOpenConversationDefaults = {
                        navController.navigate("conversation-defaults")
                    },
                    onOpenBackup = { navController.navigate("backup") },
                    onOpenLabScreenAgent = { navController.navigate("lab-screen-agent") },
                )
            }
            composable("runtimes") {
                RuntimeEnvironmentScreen(
                    onBack = navController::navigateUp,
                    onPrepareCodex = { navController.navigate("setup") },
                )
            }
            composable("lab-screen-agent") {
                LabScreenAgentScreen(onBack = navController::navigateUp)
            }
            composable("backup") {
                BackupRestoreScreen(onBack = navController::navigateUp)
            }
            composable("conversation-defaults") {
                ConversationDefaultsScreen(
                    onBack = navController::navigateUp,
                    onOpenLabScreenAgent = { navController.navigate("lab-screen-agent") },
                )
            }
            composable("models") {
                ModelsScreen(onBack = navController::navigateUp)
            }
            composable("extensions") {
                ExtensionsScreen(
                    onBack = navController::navigateUp,
                    onOpenExtension = { extensionId ->
                        navController.navigate("extensions/detail/$extensionId")
                    },
                )
            }
            composable("extensions/detail/{extensionId}") { entry ->
                val extensionId = entry.arguments?.getString("extensionId").orEmpty()
                ExtensionDetailScreen(
                    extensionId = extensionId,
                    onBack = navController::navigateUp,
                )
            }
            composable("codex-config") {
                CodexConfigScreen(onBack = navController::navigateUp)
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
    setupPreviouslyCompleted: Boolean,
): String = if (setupPreviouslyCompleted) {
    "sessions"
} else {
    "setup"
}
