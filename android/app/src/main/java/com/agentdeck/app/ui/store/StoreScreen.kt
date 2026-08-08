package com.agentdeck.app.ui.store

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.domain.setup.SetupAction
import com.agentdeck.app.ui.setup.SetupStepList
import com.agentdeck.app.ui.setup.TechnicalEnvironmentList
import com.agentdeck.app.ui.setup.primarySetupSteps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    onReady: () -> Unit = {},
    vm: StoreViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val steps = primarySetupSteps(state.report)
    val completed = steps.count { it.status == com.agentdeck.app.domain.model.EnvironmentCheckStatus.READY }
    var showDetails by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.scan() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("准备 Codex") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text(
                        "$completed / ${steps.size} 已完成",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        setupSummary(state.action),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isScanning || state.isInstalling) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.message?.let { message ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                SetupStepList(steps)
            }

            item {
                Button(
                    onClick = {
                        performPrimaryAction(
                            action = state.action,
                            context = context,
                            vm = vm,
                            grantPermission = {
                                permissionLauncher.launch(
                                    AndroidTermuxGateway.RUN_COMMAND_PERMISSION,
                                )
                            },
                            onReady = onReady,
                        )
                    },
                    enabled = !state.isScanning && !state.isInstalling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isScanning || state.isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                    } else if (state.action == SetupAction.READY) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(primaryActionLabel(state.action, state.isScanning, state.isInstalling))
                }
            }

            item {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showDetails) "收起技术详情" else "查看技术详情")
                }
            }

            if (showDetails) {
                item {
                    TechnicalEnvironmentList(state.report)
                }
            }
        }
    }
}

private fun performPrimaryAction(
    action: SetupAction,
    context: Context,
    vm: StoreViewModel,
    grantPermission: () -> Unit,
    onReady: () -> Unit,
) {
    when (action) {
        SetupAction.SCAN -> vm.scan()
        SetupAction.INSTALL_TERMUX -> {
            if (!vm.openTermuxInstallPage()) {
                Toast.makeText(context, "无法打开 Termux 安装页面", Toast.LENGTH_LONG).show()
            }
        }
        SetupAction.GRANT_PERMISSION -> grantPermission()
        SetupAction.ALLOW_TERMUX_BACKGROUND -> {
            if (vm.openTermuxAppSettings()) {
                Toast.makeText(
                    context,
                    "请进入耗电管理，允许 Termux 后台高耗电或设为不限制",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(context, "无法打开 Termux 应用设置", Toast.LENGTH_LONG).show()
            }
        }
        SetupAction.ENABLE_EXTERNAL_APPS -> {
            copy(context, vm.allowExternalAppsFixCommand())
            if (vm.openTermux()) {
                Toast.makeText(
                    context,
                    "修复命令已复制，请在 Termux 中粘贴执行",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "修复命令已复制，但无法打开 Termux",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        SetupAction.INSTALL_CODEX -> vm.installCodex()
        SetupAction.CONFIGURE_CODEX_AUTH -> vm.startCodexAuthentication().onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "无法启动 Codex 认证助手",
                Toast.LENGTH_LONG,
            ).show()
        }
        SetupAction.READY -> onReady()
    }
}

private fun primaryActionLabel(
    action: SetupAction,
    isScanning: Boolean,
    isInstalling: Boolean,
): String = when {
    isScanning -> "检测中"
    isInstalling -> "安装中"
    else -> when (action) {
        SetupAction.SCAN -> "重新检测"
        SetupAction.INSTALL_TERMUX -> "安装 Termux"
        SetupAction.GRANT_PERMISSION -> "授予调用权限"
        SetupAction.ALLOW_TERMUX_BACKGROUND -> "允许 Termux 后台运行"
        SetupAction.ENABLE_EXTERNAL_APPS -> "修复 Termux 集成"
        SetupAction.INSTALL_CODEX -> "安装、更新或修复 Codex"
        SetupAction.CONFIGURE_CODEX_AUTH -> "配置 Codex 认证"
        SetupAction.READY -> "开始对话"
    }
}

private fun setupSummary(action: SetupAction): String = when (action) {
    SetupAction.SCAN -> "正在确认当前环境"
    SetupAction.INSTALL_TERMUX -> "需要先安装 F-Droid 版 Termux"
    SetupAction.GRANT_PERMISSION -> "需要允许 AgentDeck 调用 Termux"
    SetupAction.ALLOW_TERMUX_BACKGROUND -> "系统正在限制 Termux 后台执行"
    SetupAction.ENABLE_EXTERNAL_APPS -> "需要在 Termux 中启用外部调用"
    SetupAction.INSTALL_CODEX -> "兼容版本会保留，旧版本和缺失组件将安全更新"
    SetupAction.CONFIGURE_CODEX_AUTH -> "未检测到可用账号或 API Key 配置"
    SetupAction.READY -> "运行环境已就绪"
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("agentdeck-setup", text))
}
