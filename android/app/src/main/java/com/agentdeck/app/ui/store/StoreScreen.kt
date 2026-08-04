package com.agentdeck.app.ui.store

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.RecipeSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen() {
    val recipes = remember { ServiceLocator.recipes.loadRecipes() }
    val installer = ServiceLocator.installer
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("商店") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "骨架阶段：安装会打开 Termux 执行配方脚本。请先完成设置页环境检查。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            items(recipes, key = { it.id }) { recipe ->
                RecipeCard(recipe) {
                    val result = installer.install(recipe.id)
                    val msg = result.exceptionOrNull()?.message
                        ?: "已在 Termux 启动安装：${recipe.name}"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeSummary, onInstall: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                recipe.description,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "优先级 ${recipe.priority.uppercase()}" +
                    if (recipe.dependsOn.isNotEmpty()) {
                        " · 依赖：${recipe.dependsOn.joinToString()}"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onInstall) { Text("安装 / 修复") }
        }
    }
}
