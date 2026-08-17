package com.agentdeck.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeCliStatus
import com.agentdeck.app.domain.runtime.RuntimeCliSurface
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeEnvironmentScreen(
    onBack: () -> Unit,
    onPrepareCodex: () -> Unit,
    onOpenDshWeb: (url: String) -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val runtimes by vm.runtimeStatuses.collectAsStateWithLifecycle()
    val busy by vm.runtimeActionBusy.collectAsStateWithLifecycle()
    val statusMessage by vm.runtimeActionMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<RuntimeCliStatus?>(null) }
    LaunchedEffect(Unit) { vm.refreshRuntimes() }
    LaunchedEffect(statusMessage, busy) {
        val message = statusMessage
        if (!busy && message != null && message.isNotBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val codex = runtimes.firstOrNull { it.kind.id == RuntimeCliCatalog.CODEX }
    val dsh = runtimes.firstOrNull { it.kind.id == RuntimeCliCatalog.DEEPSEEK_HARNESS }
    val pi = runtimes.firstOrNull { it.kind.id == RuntimeCliCatalog.PI }
    val comingSoon = runtimes.filter { it.kind.comingSoon }

    Scaffold(
        topBar = {
            AgentDeckTopBar(
                title = "运行环境",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.page, vertical = AppSpacing.sm)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            if (busy || statusMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(AppSpacing.md)) {
                        Text(
                            if (busy) "正在处理" else "提示",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            statusMessage ?: "请稍候…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (busy) {
                            Spacer(Modifier.height(AppSpacing.sm))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            codex?.let { status ->
                RuntimeProductCard(
                    title = "Codex",
                    subtitle = "原生聊天",
                    body = if (status.installed) {
                        "已就绪 · 占用约 ${formatStorage(status.usedBytes)}"
                    } else {
                        "未安装 · 下载约 ${formatStorage(status.selectedVersion.downloadBytes)}"
                    },
                    primaryLabel = if (status.installed) null else "安装",
                    onPrimary = if (status.installed) null else {
                        { onPrepareCodex() }
                    },
                    secondaryLabel = if (status.canDelete) "卸载" else null,
                    onSecondary = if (status.canDelete) {
                        { pendingDelete = status }
                    } else {
                        null
                    },
                    enabled = !busy,
                )
            }

            dsh?.let { status ->
                val needCodex = codex?.installed != true
                RuntimeProductCard(
                    title = "DeepSeek Harness",
                    subtitle = "本机网页助手",
                    body = when {
                        status.installed ->
                            "已就绪 · 占用约 ${formatStorage(status.usedBytes)}。" +
                                "Chat 网关用网页；退出 App 会自动停掉 Node 省内存。"
                        status.installedVersionLabel == "需补编译原生模块" ->
                            "组件已下，缺原生模块。点「修复」下载预编译包"
                        needCodex ->
                            "需先安装 Codex（共享运行根）。装好后可用网页接只支持 chat 的上游。"
                        else ->
                            "未安装 · 首次约数分钟（Node + 组件）。" +
                                "装好后可在网页里用 openai-completions 接 dots 等 chat 网关。"
                    },
                    primaryLabel = when {
                        status.installed -> "打开网页"
                        needCodex -> "去装 Codex"
                        status.installedVersionLabel == "需补编译原生模块" -> "修复"
                        else -> "安装"
                    },
                    onPrimary = {
                        when {
                            status.installed -> {
                                vm.openDshWeb { result ->
                                    result.onSuccess(onOpenDshWeb)
                                }
                            }
                            needCodex -> onPrepareCodex()
                            else -> vm.prepareDsh()
                        }
                    },
                    secondaryLabel = if (status.canDelete) "卸载" else null,
                    onSecondary = if (status.canDelete) {
                        { pendingDelete = status }
                    } else {
                        null
                    },
                    enabled = !busy,
                )
            }

            pi?.let { status ->
                val needCodex = codex?.installed != true
                RuntimeProductCard(
                    title = "pi",
                    subtitle = "终端 Agent",
                    body = when {
                        status.installed ->
                            "已就绪 · 占用约 ${formatStorage(status.usedBytes)}。" +
                                "不走 Codex Responses；chat 网关（如 dots）在 pi 内配置。" +
                                "点「验证」跑 pi --help。"
                        needCodex ->
                            "需先安装 Codex（共享运行根）"
                        else ->
                            "未安装 · 可复用 dsh 的 Node，再 npm 装 pi。" +
                                "适合 OpenAI 兼容 chat 端点，不是原生聊天会话。"
                    },
                    primaryLabel = when {
                        status.installed -> "验证"
                        needCodex -> "去装 Codex"
                        else -> "安装"
                    },
                    onPrimary = {
                        when {
                            status.installed -> vm.verifyPiHelp()
                            needCodex -> onPrepareCodex()
                            else -> vm.preparePi()
                        }
                    },
                    secondaryLabel = if (status.canDelete) "卸载" else null,
                    onSecondary = if (status.canDelete) {
                        { pendingDelete = status }
                    } else {
                        null
                    },
                    enabled = !busy,
                )
            }

            if (comingSoon.isNotEmpty()) {
                Text(
                    "即将支持",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppSpacing.xs),
                )
                comingSoon.forEach { status ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.md)) {
                            Text(status.kind.displayName, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                surfaceHint(status.kind.surface) + " · 不会自动下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { status ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("卸载 ${status.kind.displayName}？") },
            text = {
                Text(
                    when (status.kind.id) {
                        RuntimeCliCatalog.CODEX ->
                            "只删除本机组件。会话名称、人设和备份都还在。"
                        RuntimeCliCatalog.DEEPSEEK_HARNESS ->
                            "只删除 dsh 组件。会话不受影响；网页里的密钥默认保留。"
                        RuntimeCliCatalog.PI ->
                            "只删除 pi 组件。pi-home 配置默认保留。"
                        else ->
                            "只删除本机组件。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (status.kind.id) {
                            RuntimeCliCatalog.CODEX -> vm.deleteCodexRuntime()
                            RuntimeCliCatalog.DEEPSEEK_HARNESS -> vm.deleteDshRuntime()
                            RuntimeCliCatalog.PI -> vm.deletePiRuntime()
                            else -> Unit
                        }
                        pendingDelete = null
                    },
                ) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuntimeProductCard(
    title: String,
    subtitle: String,
    body: String,
    primaryLabel: String?,
    onPrimary: (() -> Unit)?,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(AppSpacing.md)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(AppSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(
                            onClick = onSecondary,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text(secondaryLabel, maxLines = 1)
                        }
                    }
                    if (primaryLabel != null && onPrimary != null) {
                        FilledTonalButton(
                            onClick = onPrimary,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(primaryLabel, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun surfaceHint(surface: RuntimeCliSurface): String = when (surface) {
    RuntimeCliSurface.NATIVE_CHAT -> "原生聊天"
    RuntimeCliSurface.WEB_UI -> "网页助手"
    RuntimeCliSurface.TERMINAL_AGENT -> "终端助手"
}

private fun formatStorage(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = (bytes + 1024L * 512L) / (1024L * 1024L)
    return if (mb >= 1024L) {
        val gb = mb / 1024.0
        String.format("%.1f GB", gb)
    } else {
        "$mb MB"
    }
}
