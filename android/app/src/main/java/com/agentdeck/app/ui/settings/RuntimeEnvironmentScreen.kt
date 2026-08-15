package com.agentdeck.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.runtime.RuntimeCliStatus
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeEnvironmentScreen(
    onBack: () -> Unit,
    onPrepareCodex: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val runtimes by vm.runtimeStatuses.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RuntimeCliStatus?>(null) }
    LaunchedEffect(Unit) { vm.refreshRuntimes() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行环境") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            item {
                Text("只准备当前要用的助手。第一次只需 Codex，其它不会自动下载。")
            }
            items(runtimes, key = { it.kind.id }) { status ->
                RuntimeCliCard(
                    status = status,
                    onPrepare = onPrepareCodex,
                    onDelete = { pendingDelete = status },
                )
            }
        }
    }
    pendingDelete?.let { status ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 " + status.kind.displayName + " 运行环境？") },
            text = { Text("只会删除本机组件，会话名称、人设和备份都还在。下次聊天前需要重新准备。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteCodexRuntime()
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuntimeCliCard(
    status: RuntimeCliStatus,
    onPrepare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(status.kind.displayName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(AppSpacing.xs))
        Text(
            when {
                status.kind.comingSoon -> "即将支持，现在不会占用下载或磁盘"
                status.installed -> (status.installedVersionLabel ?: "已准备") +
                    " · 本机约 " + formatMb(status.usedBytes)
                else -> "未下载 · 准备大约 " + formatMb(status.selectedVersion.downloadBytes)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.kind.versions.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text("版本", style = MaterialTheme.typography.labelLarge)
            status.kind.versions.forEach { version ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = version.selected, onClick = null, enabled = false)
                    Column {
                        Text(version.label)
                        Text(
                            version.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(AppSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            if (status.kind.available && !status.installed) {
                Button(onClick = onPrepare) { Text("准备 Codex") }
            }
            if (status.canDelete) {
                OutlinedButton(onClick = onDelete) { Text("删除本机组件") }
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    return ((bytes + 1024L * 512L) / (1024L * 1024L)).toString() + " MB"
}
