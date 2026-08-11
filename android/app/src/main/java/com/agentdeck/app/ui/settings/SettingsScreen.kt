package com.agentdeck.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.host.HostWriteApprovalMode
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenCodexConfig: () -> Unit = {},
    onOpenConversationDefaults: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val experienceLevel by vm.experienceLevel.collectAsStateWithLifecycle()
    val permission by vm.codexPermissionLevel.collectAsStateWithLifecycle()
    val permissionPresentation = codexPermissionPresentation(permission)

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = AppSpacing.sm),
        ) {
            item { SectionLabel("开始使用") }
            item {
                SettingsDestination(
                    title = "运行环境",
                    summary = if (state.canStartChat) {
                        "已就绪，可以创建对话"
                    } else {
                        "需要安装或修复后才能对话"
                    },
                    icon = {
                        Icon(
                            if (state.canStartChat) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = if (state.canStartChat) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    onClick = onOpenSetup,
                )
            }
            item { HorizontalDivider() }
            item {
                SettingsDestination(
                    title = "模型服务",
                    summary = "登录 ChatGPT 或填写 API 密钥",
                    icon = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    onClick = onOpenModels,
                )
            }
            item { HorizontalDivider() }
            item { SectionLabel("会话") }
            item {
                SettingsDestination(
                    title = "会话高级设置",
                    summary = "默认权限：${permissionPresentation.title}" +
                        if (experienceLevel.advancedEnabled) " · 高级已开" else "",
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    onClick = onOpenConversationDefaults,
                )
            }
            if (experienceLevel.advancedEnabled) {
                item { HorizontalDivider() }
                item {
                    SettingsDestination(
                        title = "Codex 配置文件",
                        summary = "给熟悉配置的人用，一般可忽略",
                        icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                        onClick = onOpenCodexConfig,
                    )
                }
            }
            item { HorizontalDivider() }
            item { SectionLabel("关于") }
            item {
                ListItem(
                    headlineContent = {
                        Text(if (BuildConfig.HOST_LAB) "AgentDeck Lab" else "AgentDeck")
                    },
                    supportingContent = {
                        Text(
                            buildString {
                                append("版本 ${BuildConfig.VERSION_NAME}")
                                if (BuildConfig.HOST_LAB) {
                                    append(" · 高权限实验通道")
                                } else {
                                    append(" · 安全通道（L1）")
                                }
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDefaultsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val experienceLevel by vm.experienceLevel.collectAsStateWithLifecycle()
    val selectedPermission by vm.codexPermissionLevel.collectAsStateWithLifecycle()
    val hostWorkspaceEnabled by vm.hostWorkspaceEnabled.collectAsStateWithLifecycle()
    val hostWriteApprovalMode by vm.hostWriteApprovalMode.collectAsStateWithLifecycle()
    val workspaceGrants by vm.workspaceGrants.collectAsStateWithLifecycle()
    val labRisk by vm.labRiskAccepted.collectAsStateWithLifecycle()
    val labIntent by vm.labIntentEnabled.collectAsStateWithLifecycle()
    val labUi by vm.labUiEnabled.collectAsStateWithLifecycle()
    val labPriv by vm.labPrivEnabled.collectAsStateWithLifecycle()
    val openTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: "工作区"
            vm.addWorkspaceGrant(uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话高级设置") },
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
            contentPadding = PaddingValues(vertical = AppSpacing.sm),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("开启高级选项") },
                    supportingContent = {
                        Text("工作区等可选功能")
                    },
                    leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
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
            item { HorizontalDivider() }
            item { SectionLabel("新会话默认权限") }
            CodexPermissionLevel.entries.forEach { level ->
                item(key = level.name) {
                    val presentation = codexPermissionPresentation(level)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setCodexPermissionLevel(level) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selectedPermission == level,
                            onClick = { vm.setCodexPermissionLevel(level) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(presentation.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                presentation.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (experienceLevel.advancedEnabled) {
                item { HorizontalDivider() }
                item { SectionLabel("本机工作区") }
                item {
                    ListItem(
                        headlineContent = { Text("允许访问所选文件夹") },
                        supportingContent = {
                            Text("读写你选的手机文件夹，可随时关闭")
                        },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = hostWorkspaceEnabled,
                                onCheckedChange = vm::setHostWorkspaceEnabled,
                            )
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            openTree.launch(null)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Text(if (workspaceGrants.isEmpty()) "选择文件夹" else "更换文件夹")
                    }
                }
                if (hostWorkspaceEnabled) {
                    item {
                        Text(
                            "写入时",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setHostWriteApprovalMode(HostWriteApprovalMode.ALWAYS_ASK)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = hostWriteApprovalMode == HostWriteApprovalMode.ALWAYS_ASK,
                                onClick = {
                                    vm.setHostWriteApprovalMode(HostWriteApprovalMode.ALWAYS_ASK)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("每次询问", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "改文件前先确认",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setHostWriteApprovalMode(HostWriteApprovalMode.NEVER_ASK)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = hostWriteApprovalMode == HostWriteApprovalMode.NEVER_ASK,
                                onClick = {
                                    vm.setHostWriteApprovalMode(HostWriteApprovalMode.NEVER_ASK)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("不再询问", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "直接写入所选文件夹",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                workspaceGrants.forEach { grant ->
                    item(key = grant.id) {
                        ListItem(
                            headlineContent = { Text(grant.displayName) },
                            supportingContent = { Text("已授权") },
                            trailingContent = {
                                TextButton(onClick = { vm.revokeWorkspaceGrant(grant.id) }) {
                                    Text("撤销")
                                }
                            },
                        )
                    }
                }
            }
            if (vm.isLabBuild && experienceLevel.advancedEnabled) {
                item { HorizontalDivider() }
                item { SectionLabel("Lab 高权限（实验）") }
                item {
                    ListItem(
                        headlineContent = { Text("开发者模式") },
                        supportingContent = { Text("L2–L4 需要开发者模式") },
                        trailingContent = {
                            Switch(
                                checked = experienceLevel == ExperienceLevel.DEVELOPER,
                                onCheckedChange = vm::setDeveloperEnabled,
                            )
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("我理解 Lab 风险") },
                        supportingContent = {
                            Text("可能打开链接、读取界面并执行受限命令。仅用于测试机。")
                        },
                        trailingContent = {
                            Switch(
                                checked = labRisk,
                                onCheckedChange = vm::setLabRiskAccepted,
                                enabled = experienceLevel == ExperienceLevel.DEVELOPER,
                            )
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("L2 Intent 协作") },
                        supportingContent = { Text("打开 https 链接 / 系统分享文本") },
                        trailingContent = {
                            Switch(
                                checked = labIntent,
                                onCheckedChange = vm::setLabIntentEnabled,
                                enabled = labRisk,
                            )
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("L3 屏幕代理") },
                        supportingContent = { Text("还需在系统无障碍中开启 AgentDeck Lab") },
                        trailingContent = {
                            Switch(
                                checked = labUi,
                                onCheckedChange = vm::setLabUiEnabled,
                                enabled = labRisk,
                            )
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("L4 特权壳（白名单）") },
                        supportingContent = { Text("App UID 下 id/uname/getprop/pm list 等") },
                        trailingContent = {
                            Switch(
                                checked = labPriv,
                                onCheckedChange = vm::setLabPrivEnabled,
                                enabled = labRisk,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDestination(
    title: String,
    summary: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = icon,
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
