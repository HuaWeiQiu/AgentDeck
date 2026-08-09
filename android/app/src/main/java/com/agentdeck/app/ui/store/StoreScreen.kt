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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.domain.setup.SetupAction
import com.agentdeck.app.ui.setup.customerSetupPresentation
import com.agentdeck.app.ui.setup.customerSetupSteps
import com.agentdeck.app.ui.setup.SetupStepList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: (() -> Unit)? = null,
    onReady: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    vm: StoreViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val presentation = customerSetupPresentation(state)
    val steps = customerSetupSteps(state.report)
    val completed = steps.count { it.status == com.agentdeck.app.domain.model.EnvironmentCheckStatus.READY }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.scan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("准备 AgentDeck") },
                navigationIcon = {
                    onBack?.let { navigateBack ->
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
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
                        presentation.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        presentation.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$completed / ${steps.size} 已完成",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isScanning || state.isInstalling) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            presentation.errorMessage?.let { error ->
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
                            onOpenModels = onOpenModels,
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
                    Text(presentation.primaryActionLabel)
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
    onOpenModels: () -> Unit,
) {
    when (action) {
        SetupAction.SCAN -> vm.scan()
        SetupAction.INSTALL_TERMUX -> {
            if (!vm.openTermuxInstallPage()) {
                Toast.makeText(context, "无法打开运行组件安装页", Toast.LENGTH_LONG).show()
            }
        }
        SetupAction.GRANT_PERMISSION -> grantPermission()
        SetupAction.ALLOW_TERMUX_BACKGROUND -> {
            if (vm.openTermuxAppSettings()) {
                Toast.makeText(
                    context,
                    "请允许后台运行或设为不限制",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(context, "无法打开运行组件设置", Toast.LENGTH_LONG).show()
            }
        }
        SetupAction.ENABLE_EXTERNAL_APPS -> {
            copy(context, vm.allowExternalAppsFixCommand())
            if (vm.openTermux()) {
                Toast.makeText(
                    context,
                    "设置命令已复制，请粘贴执行后返回 AgentDeck",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "设置命令已复制，请打开运行组件完成设置",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        SetupAction.INSTALL_CODEX -> vm.installCodex()
        SetupAction.CONFIGURE_CODEX_AUTH -> {
            if (vm.usesEmbeddedRuntime()) {
                onOpenModels()
            } else {
                vm.startCodexAuthentication().onFailure {
                    Toast.makeText(
                        context,
                        "无法打开模型连接流程，请稍后重试",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        SetupAction.UNSUPPORTED_DEVICE -> Toast.makeText(
            context,
            "当前测试版仅支持 ARM64 Android 设备",
            Toast.LENGTH_LONG,
        ).show()
        SetupAction.READY -> onReady()
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("agentdeck-setup", text))
}
