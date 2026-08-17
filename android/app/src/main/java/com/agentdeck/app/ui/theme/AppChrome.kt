package com.agentdeck.app.ui.theme

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared chrome: compact top bars that still respect the status bar under edge-to-edge.
 * Prefer this over raw Material3 TopAppBar so title density stays consistent app-wide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDeckTopBar(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    centerTitle: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    AgentDeckTopBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        centerTitle = centerTitle,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDeckTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    centerTitle: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (centerTitle) {
        CenterAlignedTopAppBar(
            title = title,
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = colors,
            windowInsets = WindowInsets.statusBars,
            scrollBehavior = scrollBehavior,
            expandedHeight = 56.dp,
        )
    } else {
        TopAppBar(
            title = title,
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = colors,
            windowInsets = WindowInsets.statusBars,
            scrollBehavior = scrollBehavior,
            expandedHeight = 56.dp,
        )
    }
}
