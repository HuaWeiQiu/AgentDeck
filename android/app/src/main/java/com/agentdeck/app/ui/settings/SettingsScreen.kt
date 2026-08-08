package com.agentdeck.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.ui.setup.TechnicalEnvironmentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val environmentReady = state.isReady

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    "AgentDeck ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Codex 运行环境") },
                    supportingContent = {
                        Text(if (environmentReady) "已就绪" else "需要完成准备")
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenSetup),
                )
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text("CLI 配置") },
                    supportingContent = { Text("Provider、Base URL 和默认模型") },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenProfiles),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = vm::scan,
                        enabled = !state.isScanning && !state.isInstalling,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text(if (state.isScanning) "检测中" else "重新检测")
                    }
                    OutlinedButton(onClick = { vm.openTermux() }) {
                        Icon(Icons.Filled.Terminal, contentDescription = null)
                        Text("打开 Termux")
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text("环境详情", style = MaterialTheme.typography.titleSmall)
                    TechnicalEnvironmentList(
                        report = state.report,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
