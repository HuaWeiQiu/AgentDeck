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
import com.agentdeck.app.domain.model.CodexPermissionLevel
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
            item { SectionLabel("连接与运行") }
            item {
                SettingsDestination(
                    title = "模型连接",
                    summary = "ChatGPT、OpenAI API 与第三方 Responses 服务",
                    icon = { Icon(Icons.Filled.Hub, contentDescription = null) },
                    onClick = onOpenModels,
                )
            }
            item { HorizontalDivider() }
            item {
                SettingsDestination(
                    title = "内嵌运行环境",
                    summary = if (state.canStartChat) "本机 Runtime · 可用" else "本机 Runtime · 需要完成准备",
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
            item { SectionLabel("对话与 Codex") }
            item {
                SettingsDestination(
                    title = "对话默认值",
                    summary = "${permissionPresentation.title} · " +
                        if (experienceLevel.advancedEnabled) "显示高级选项" else "标准选项",
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    onClick = onOpenConversationDefaults,
                )
            }
            item { HorizontalDivider() }
            item {
                SettingsDestination(
                    title = "Codex 参数",
                    summary = "agentdeck.config.toml · 可选参数 · 启动前同步",
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    onClick = onOpenCodexConfig,
                )
            }
            item { HorizontalDivider() }
            item { SectionLabel("关于") }
            item {
                ListItem(
                    headlineContent = { Text("AgentDeck") },
                    supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
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
    val workspaceGrants by vm.workspaceGrants.collectAsStateWithLifecycle()
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
                title = { Text("对话默认值") },
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
                    headlineContent = { Text("显示高级选项") },
                    supportingContent = { Text("在对话编辑器中显示项目目录等技术选项") },
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
            item { SectionLabel("默认 Codex 权限") }
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
                            if (experienceLevel.advancedEnabled) {
                                Text(
                                    presentation.technicalSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (experienceLevel.advancedEnabled) {
                item { HorizontalDivider() }
                item { SectionLabel("本机工作区（L1）") }
                item {
                    ListItem(
                        headlineContent = { Text("允许访问所选文件夹") },
                        supportingContent = {
                            Text(
                                "仅你选中的目录；与 Codex「完全访问」无关。默认关闭，可随时撤销。",
                            )
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
                        Text(if (workspaceGrants.isEmpty()) "选择工作区文件夹" else "更换工作区文件夹")
                    }
                }
                workspaceGrants.forEach { grant ->
                    item(key = grant.id) {
                        ListItem(
                            headlineContent = { Text(grant.displayName) },
                            supportingContent = { Text("已授权 · 可撤销") },
                            trailingContent = {
                                TextButton(onClick = { vm.revokeWorkspaceGrant(grant.id) }) {
                                    Text("撤销")
                                }
                            },
                        )
                    }
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
