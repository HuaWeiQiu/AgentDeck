package com.agentdeck.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.EnvironmentCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val termux = ServiceLocator.termux
    val probe = ServiceLocator.envProbe
    val launcher = ServiceLocator.launcher
    val context = LocalContext.current
    var report by remember { mutableStateOf(probe.scan()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
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
                Text(
                    "轻量 Termux 启动器 · 卡片进入 CLI 聊天会话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { report = probe.scan() }) { Text("重新检测") }
                    if (!termux.isTermuxInstalled()) {
                        OutlinedButton(onClick = { termux.openTermuxInstallPage() }) {
                            Text("安装 Termux")
                        }
                    } else {
                        OutlinedButton(onClick = { termux.openTermux() }) {
                            Text("打开 Termux")
                        }
                    }
                }
            }

            items(report.checks, key = { it.id }) { check ->
                CheckCard(check)
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("修复 allow-external-apps", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在 Termux 中执行以下命令后重开 AgentDeck：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        val cmd = probe.allowExternalAppsFixCommand()
                        Text(cmd, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                copy(context, cmd)
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("复制命令") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("安装 Codex 启动 wrapper", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "把内置 codex-ubuntu.sh 写入 Termux ~/.agentdeck/wrappers/",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val cmd = launcher.wrapperBootstrapCommand()
                                copy(context, cmd)
                                Toast.makeText(context, "wrapper 安装脚本已复制，请粘贴到 Termux 执行", Toast.LENGTH_LONG).show()
                            },
                        ) { Text("复制 wrapper 安装脚本") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckCard(check: EnvironmentCheck) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                (if (check.ok) "✓ " else "○ ") + check.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (check.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(check.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("agentdeck", text))
}
