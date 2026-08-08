package com.agentdeck.app.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.model.RecipeSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    vm: StoreViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("环境与工具") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.recipes, key = { it.id }) { recipe ->
                RecipeCard(
                    recipe = recipe,
                    install = state.installs[recipe.id] ?: RecipeInstallUiState(),
                    enabled = recipe.available && state.activeRecipeId == null,
                    onInstall = { vm.install(recipe.id) },
                )
            }
        }
    }
}

@Composable
private fun RecipeCard(
    recipe: RecipeSummary,
    install: RecipeInstallUiState,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(recipe.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${recipe.version} · 优先级 ${recipe.priority.uppercase()}" +
                    if (recipe.dependsOn.isNotEmpty()) {
                        " · 依赖：${recipe.dependsOn.joinToString()}"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (install.status != InstallStatus.IDLE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    install.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (install.status) {
                        InstallStatus.FAILED -> MaterialTheme.colorScheme.error
                        InstallStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onInstall,
                enabled = enabled,
            ) {
                if (install.status == InstallStatus.INSTALLING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (!recipe.available) {
                        "计划中"
                    } else {
                        when (install.status) {
                            InstallStatus.INSTALLING -> "安装中"
                            InstallStatus.SUCCEEDED -> "重新验证"
                            InstallStatus.FAILED -> "重试"
                            InstallStatus.IDLE -> "安装 / 修复"
                        }
                    },
                )
            }
        }
    }
}
