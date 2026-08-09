package com.agentdeck.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.runtime.RuntimeSelection
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.ui.setup.TechnicalEnvironmentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val experienceLevel by vm.experienceLevel.collectAsState()
    val codexPermissionLevel by vm.codexPermissionLevel.collectAsState()
    val runtimeSelection by vm.runtimeSelection.collectAsState()
    val environmentReady = state.canStartChat
    val context = LocalContext.current
    var permissionDialogVisible by rememberSaveable { mutableStateOf(false) }
    var runtimeDialogVisible by rememberSaveable { mutableStateOf(false) }
    val permissionPresentation = codexPermissionPresentation(codexPermissionLevel)

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
                SectionLabel("服务")
                ListItem(
                    headlineContent = { Text("本机运行环境") },
                    supportingContent = {
                        Text(if (environmentReady) "可用" else "需要完成准备")
                    },
                    leadingContent = {
                        Icon(
                            if (environmentReady) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (environmentReady) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
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
                    headlineContent = { Text("模型服务") },
                    supportingContent = { Text("Sub2API、Responses 兼容服务与默认模型") },
                    leadingContent = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenModels),
                )
            }
            item { HorizontalDivider() }

            item {
                SectionLabel("偏好")
                ListItem(
                    headlineContent = { Text("Codex 权限") },
                    supportingContent = {
                        Text("${permissionPresentation.title} · ${permissionPresentation.description}")
                    },
                    leadingContent = { Icon(Icons.Filled.Security, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable { permissionDialogVisible = true },
                )
            }
            item { HorizontalDivider() }

            item {
                ListItem(
                    headlineContent = { Text("高级设置") },
                    supportingContent = { Text("模型、项目与兼容运行环境") },
                    trailingContent = {
                        Switch(
                            checked = experienceLevel.advancedEnabled,
                            onCheckedChange = vm::setAdvancedEnabled,
                        )
                    },
                    modifier = Modifier.clickable {
                        vm.setAdvancedEnabled(!experienceLevel.advancedEnabled)
                    },
                )
            }

            if (experienceLevel.advancedEnabled) {
                item {
                    SectionLabel("运行环境")
                    ListItem(
                        headlineContent = { Text("Codex 运行方式") },
                        supportingContent = {
                            Text(
                                when (runtimeSelection) {
                                    RuntimeSelection.EMBEDDED -> "内嵌运行环境（ARM64 测试）"
                                    RuntimeSelection.TERMUX_COMPATIBILITY -> "Termux 兼容模式"
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable { runtimeDialogVisible = true },
                    )
                }
                item { HorizontalDivider() }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = vm::scan,
                            enabled = !state.isScanning && !state.isInstalling,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(if (state.isScanning) "检查中" else "重新检查")
                        }
                        if (runtimeSelection == RuntimeSelection.TERMUX_COMPATIBILITY) {
                            OutlinedButton(
                                onClick = {
                                    if (!vm.openTermux()) {
                                        Toast.makeText(
                                            context,
                                            "无法打开兼容运行组件",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Terminal, contentDescription = null)
                                Text("兼容运行组件")
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("运行环境详情", style = MaterialTheme.typography.titleSmall)
                        TechnicalEnvironmentList(
                            report = state.report,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                SectionLabel("关于")
                ListItem(
                    headlineContent = { Text("AgentDeck") },
                    supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                )
            }
        }
    }

    if (permissionDialogVisible) {
        CodexPermissionDialog(
            selected = codexPermissionLevel,
            showTechnicalDetails = experienceLevel.advancedEnabled,
            onSelect = {
                vm.setCodexPermissionLevel(it)
                permissionDialogVisible = false
            },
            onDismiss = { permissionDialogVisible = false },
        )
    }

    if (runtimeDialogVisible) {
        RuntimeSelectionDialog(
            selected = runtimeSelection,
            onSelect = {
                vm.setRuntimeSelection(it)
                runtimeDialogVisible = false
            },
            onDismiss = { runtimeDialogVisible = false },
        )
    }
}

@Composable
private fun RuntimeSelectionDialog(
    selected: RuntimeSelection,
    onSelect: (RuntimeSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex 运行方式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RuntimeSelection.entries.forEach { selection ->
                    val (title, detail) = when (selection) {
                        RuntimeSelection.EMBEDDED -> "内嵌运行环境" to
                            "无需安装 Termux；首次准备会下载约 122 MB"
                        RuntimeSelection.TERMUX_COMPATIBILITY -> "Termux 兼容模式" to
                            "保留现有 Termux、Ubuntu 与 Codex 环境"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(selection) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selected == selection,
                            onClick = { onSelect(selection) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CodexPermissionDialog(
    selected: CodexPermissionLevel,
    showTechnicalDetails: Boolean,
    onSelect: (CodexPermissionLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex 权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CodexPermissionLevel.entries.forEach { level ->
                    val presentation = codexPermissionPresentation(level)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(level) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selected == level,
                            onClick = { onSelect(level) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(presentation.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                presentation.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (showTechnicalDetails) {
                                Text(
                                    presentation.technicalSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
