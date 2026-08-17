package com.agentdeck.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
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
import com.agentdeck.app.ui.chat.LightChatScreen
import com.agentdeck.app.ui.chat.PiChatScreen
import com.agentdeck.app.ui.config.CodexConfigScreen
import com.agentdeck.app.ui.sessions.SessionsScreen
import com.agentdeck.app.ui.settings.SettingsScreen
import com.agentdeck.app.ui.settings.BackupRestoreScreen
import com.agentdeck.app.ui.settings.ConversationDefaultsScreen
import com.agentdeck.app.ui.settings.LabScreenAgentScreen
import com.agentdeck.app.ui.settings.RuntimeEnvironmentScreen
import com.agentdeck.app.ui.runtime.LoopbackWebScreen
import com.agentdeck.app.ui.models.ModelsScreen
import com.agentdeck.app.ui.extensions.ExtensionDetailScreen
import com.agentdeck.app.ui.extensions.ExtensionsScreen
import com.agentdeck.app.ui.store.SetupScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    // Edge-to-edge is on (MainActivity). Child screens own status-bar insets via
    // their TopAppBar. Root Scaffold must NOT also apply safeDrawing top padding,
    // or every page gets a double status-bar gap under the system icons.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute in standardTopLevelRoutes) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = NavigationBarDefaults.Elevation,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigateTopLevel(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // Only bottomBar height (and 0 insets) — not a second status-bar pad.
            modifier = Modifier.padding(padding),
        ) {
            composable("sessions") {
                SessionsScreen(
                    onOpenSetup = { navController.navigate("setup") { launchSingleTop = true } },
                    onOpenModels = { navController.navigate("models") { launchSingleTop = true } },
                    onOpenChat = { cardId -> navController.navigate("chat/$cardId") },
                    onOpenDshWeb = { url ->
                        val encoded = android.net.Uri.encode(url)
                        navController.navigate("dsh-web/$encoded")
                    },
                    onOpenPiChat = { cardId, title ->
                        val encTitle = URLEncoder.encode(
                            title.ifBlank { "pi" },
                            StandardCharsets.UTF_8.name(),
                        )
                        navController.navigate("pi-chat/$cardId/$encTitle")
                    },
                    onOpenLightChat = { cardId ->
                        navController.navigate("light-chat-session/$cardId")
                    },
                    onOpenRuntimes = { navController.navigate("runtimes") },
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
                    onOpenDshWeb = { url ->
                        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
                        navController.navigate("dsh-web/$encoded")
                    },
                )
            }
            composable("dsh-web/{url}") { entry ->
                val encoded = entry.arguments?.getString("url").orEmpty()
                val url = runCatching {
                    URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                }.getOrDefault("")
                LoopbackWebScreen(
                    title = "DeepSeek Harness",
                    url = url,
                    onBack = {
                        ServiceLocator.runtimeInventory.dshSupervisor().stop()
                        navController.navigateUp()
                    },
                    // Always tear down Node/PRoot when the screen is disposed (system back, etc.).
                    onCloseSession = {
                        ServiceLocator.runtimeInventory.dshSupervisor().stop()
                    },
                    onNewSession = { reload ->
                        // Wipe on-disk dsh sessions then reload SPA — phone UI is hard to tap.
                        ServiceLocator.runtimeInventory.dshSupervisor().clearChatSessions()
                        reload()
                    },
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
                ModelsScreen(
                    onBack = navController::navigateUp,
                    onOpenLightChat = { profileId, modelId, title ->
                        val encTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
                        val encModel = URLEncoder.encode(modelId, StandardCharsets.UTF_8.name())
                        navController.navigate("light-chat/$profileId/$encModel/$encTitle")
                    },
                )
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
            composable("light-chat-session/{cardId}") { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                LightChatScreen(
                    onBack = navController::navigateUp,
                    cardId = cardId,
                )
            }
            composable("light-chat/{profileId}/{modelId}/{title}") { entry ->
                val profileId = entry.arguments?.getString("profileId").orEmpty()
                val modelEnc = entry.arguments?.getString("modelId").orEmpty()
                val titleEnc = entry.arguments?.getString("title").orEmpty()
                val modelId = runCatching {
                    URLDecoder.decode(modelEnc, StandardCharsets.UTF_8.name())
                }.getOrDefault(modelEnc)
                val title = runCatching {
                    URLDecoder.decode(titleEnc, StandardCharsets.UTF_8.name())
                }.getOrDefault(titleEnc)
                LightChatScreen(
                    onBack = navController::navigateUp,
                    profileId = profileId,
                    modelId = modelId,
                    title = title,
                )
            }
            composable("chat/{cardId}") { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                ChatScreen(
                    cardId = cardId,
                    onBack = navController::navigateUp,
                )
            }
            composable("pi-chat/{cardId}/{title}") { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                val title = runCatching {
                    URLDecoder.decode(
                        entry.arguments?.getString("title").orEmpty(),
                        StandardCharsets.UTF_8.name(),
                    )
                }.getOrDefault("pi")
                PiChatScreen(
                    cardId = cardId,
                    title = title.ifBlank { "pi" },
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
