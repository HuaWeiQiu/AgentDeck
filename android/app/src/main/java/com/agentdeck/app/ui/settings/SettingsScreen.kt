package com.agentdeck.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val report = state.report
    val termux = ServiceLocator.termux
    val probe = ServiceLocator.envProbe
    val launcher = ServiceLocator.launcher
    val context = LocalContext.current
    val termuxInstalled = report.check("termux_installed")?.ok == true
    val permissionGranted = report.check("termux_run_command_permission")?.ok == true
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.scan() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("环境与设置") }) },
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
                    "AgentDeck ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::scan,
                        enabled = !state.isScanning,
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isScanning) "检测中" else "重新检测")
                    }
                    when {
                        !termuxInstalled -> {
                            OutlinedButton(onClick = { termux.openTermuxInstallPage() }) {
                                Text("安装 Termux")
                            }
                        }

                        !permissionGranted -> {
                            OutlinedButton(
                                onClick = {
                                    permissionLauncher.launch(
                                        AndroidTermuxGateway.RUN_COMMAND_PERMISSION,
                                    )
                                },
                            ) {
                                Text("授予权限")
                            }
                        }

                        else -> {
                            OutlinedButton(onClick = { termux.openTermux() }) {
                                Text("打开 Termux")
                            }
                        }
                    }
                }
            }

            items(report.checks, key = { it.id }) { check ->
                CheckCard(check)
            }

            if (termuxInstalled && report.check("allow_external_apps")?.ok != true) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("修复 allow-external-apps", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            val command = probe.allowExternalAppsFixCommand()
                            Button(
                                onClick = {
                                    copy(context, command)
                                    Toast.makeText(
                                        context,
                                        "修复命令已复制",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) { Text("复制修复命令") }
                        }
                    }
                }
            }

            if (termuxInstalled && report.check("codex_wrapper")?.ok != true) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Codex 启动 wrapper", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    copy(context, launcher.wrapperBootstrapCommand())
                                    Toast.makeText(
                                        context,
                                        "wrapper 安装脚本已复制",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) { Text("复制安装脚本") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckCard(check: EnvironmentCheck) {
    val appearance = checkAppearance(check.status)
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = appearance.icon,
                contentDescription = appearance.label,
                tint = appearance.color,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(check.label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(check.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun checkAppearance(status: EnvironmentCheckStatus): CheckAppearance {
    return when (status) {
        EnvironmentCheckStatus.UNKNOWN -> CheckAppearance(
            Icons.Filled.RadioButtonUnchecked,
            "未知",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnvironmentCheckStatus.CHECKING -> CheckAppearance(
            Icons.Filled.HourglassTop,
            "检测中",
            MaterialTheme.colorScheme.primary,
        )
        EnvironmentCheckStatus.READY -> CheckAppearance(
            Icons.Filled.CheckCircle,
            "就绪",
            MaterialTheme.colorScheme.primary,
        )
        EnvironmentCheckStatus.ACTION_REQUIRED -> CheckAppearance(
            Icons.Filled.WarningAmber,
            "需要操作",
            MaterialTheme.colorScheme.tertiary,
        )
        EnvironmentCheckStatus.BLOCKED -> CheckAppearance(
            Icons.Filled.Block,
            "被阻塞",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnvironmentCheckStatus.ERROR -> CheckAppearance(
            Icons.Filled.ErrorOutline,
            "错误",
            MaterialTheme.colorScheme.error,
        )
    }
}

private data class CheckAppearance(
    val icon: ImageVector,
    val label: String,
    val color: Color,
)

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("agentdeck", text))
}
