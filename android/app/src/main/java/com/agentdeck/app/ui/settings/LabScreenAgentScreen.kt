package com.agentdeck.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreenAgentScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val enabled by vm.labUiEnabled.collectAsStateWithLifecycle()
    val risk by vm.labRiskAccepted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("屏幕 Agent") },
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
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("实验功能，只在 Lab 包里。AI 只能操作你允许的应用，密码、验证码、支付和系统设置会立刻停下来交给你。")
            Spacer(Modifier.height(AppSpacing.md))
            Text(
                "不会自动打开无障碍。需要你自己去系统设置打开 AgentDeck Lab 屏幕代理，再回到这里打开开关。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.lg))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            ) {
                Text("打开系统无障碍设置")
            }
            Spacer(Modifier.height(AppSpacing.md))
            Button(
                onClick = { vm.setLabUiEnabled(!enabled) },
                enabled = risk || enabled,
            ) {
                Text(if (enabled) "关闭屏幕 Agent" else "开启屏幕 Agent")
            }
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                if (enabled) "已开启。聊天里的 ui.snapshot / ui.click 才会执行。" else "默认关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
