package com.agentdeck.app.ui.extensions

import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.extensions.ExtensionAuthType
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ExtensionToolAccess
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit,
    onOpenExtension: (String) -> Unit,
    vm: ExtensionsViewModel = viewModel(),
) {
    val extensions by vm.extensions.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var addMenuVisible by remember { mutableStateOf(false) }
    var remoteEditorVisible by remember { mutableStateOf(false) }
    var localEditorVisible by remember { mutableStateOf(false) }
    val importSkill = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::importSkill)
    }

    LaunchedEffect(error) {
        error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            AgentDeckTopBar(
                title = "扩展",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { addMenuVisible = true }, enabled = !busy) {
                        Icon(Icons.Filled.Add, contentDescription = "添加扩展")
                    }
                    DropdownMenu(
                        expanded = addMenuVisible,
                        onDismissRequest = { addMenuVisible = false },
            ) {
                        DropdownMenuItem(
                            text = { Text("导入 SKILL.md") },
                            leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                            onClick = {
                                addMenuVisible = false
                                importSkill.launch(
                                    arrayOf("text/markdown", "text/plain", "application/octet-stream"),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("远程 MCP") },
                            leadingIcon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                            onClick = {
                                addMenuVisible = false
                                remoteEditorVisible = true
                            },
                        )
                        if (vm.supportsLocalMcp) {
                            DropdownMenuItem(
                                text = { Text("本地 MCP") },
                                leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                                onClick = {
                                    addMenuVisible = false
                                    localEditorVisible = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null && extensions.isEmpty() -> {
                ExtensionLoadError(
                    message = requireNotNull(loadError),
                    onRetry = vm::retryLoad,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            extensions.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(AppSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Code, contentDescription = null)
                    Text(
                        "尚未添加扩展",
                        modifier = Modifier.padding(top = AppSpacing.sm),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = AppSpacing.sm),
                ) {
                    ExtensionKind.entries.forEach { kind ->
                        val group = extensions.filter { it.kind == kind }
                        if (group.isNotEmpty()) {
                            item(key = "kind-${kind.name}") {
                                SectionLabel(extensionKindTitle(kind))
                            }
                            items(group, key = ManagedExtension::id) { extension ->
                                ExtensionRow(extension, onClick = { onOpenExtension(extension.id) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (remoteEditorVisible) {
        RemoteMcpEditorDialog(
            busy = busy,
            onDismiss = { remoteEditorVisible = false },
            onDiscover = vm::discoverRemote,
            onSave = { name, description, url, auth, token, tools ->
                vm.saveRemote(
                    existingId = null,
                    name = name,
                    description = description,
                    url = url,
                    authType = auth,
                    bearerToken = token,
                    discoveredTools = tools,
                ) { result ->
                    if (result.isSuccess) remoteEditorVisible = false
                }
            },
        )
    }

    if (localEditorVisible) {
        LocalMcpEditorDialog(
            busy = busy,
            onDismiss = { localEditorVisible = false },
            onSave = { name, description, command, args ->
                vm.saveLocal(
                    existingId = null,
                    name = name,
                    description = description,
                    command = command,
                    args = args,
                ) { result ->
                    if (result.isSuccess) localEditorVisible = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionDetailScreen(
    extensionId: String,
    onBack: () -> Unit,
    vm: ExtensionsViewModel = viewModel(),
) {
    val extensions by vm.extensions.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extension = extensions.firstOrNull { it.id == extensionId }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var remoteEditorVisible by remember { mutableStateOf(false) }
    var localEditorVisible by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            AgentDeckTopBar(
                title = extension?.name ?: "扩展详情",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (extension?.kind == ExtensionKind.REMOTE_MCP ||
                        extension?.kind == ExtensionKind.LOCAL_MCP
                    ) {
                        IconButton(
                            onClick = {
                                remoteEditorVisible = extension.kind == ExtensionKind.REMOTE_MCP
                                localEditorVisible = extension.kind == ExtensionKind.LOCAL_MCP
                            },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑扩展")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            loadError != null && extension == null -> {
                ExtensionLoadError(
                    message = requireNotNull(loadError),
                    onRetry = vm::retryLoad,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            extension == null -> {
                Text("扩展不存在", modifier = Modifier.padding(padding).padding(AppSpacing.lg))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = AppSpacing.sm),
                ) {
                    item {
                        ListItem(
                            headlineContent = { Text(extension.name) },
                            supportingContent = {
                                Text(extension.description.ifBlank { extensionKindTitle(extension.kind) })
                            },
                            leadingContent = {
                                Icon(extensionKindIcon(extension.kind), contentDescription = null)
                            },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        ListItem(
                            headlineContent = { Text("启用扩展") },
                            supportingContent = { Text(extensionStatusLabel(extension)) },
                            trailingContent = {
                                Switch(
                                    checked = extension.enabled,
                                    onCheckedChange = { vm.setEnabled(extension.id, it) },
                                    enabled = !busy,
                                )
                            },
                            modifier = Modifier.clickable(enabled = !busy) {
                                vm.setEnabled(extension.id, !extension.enabled)
                            },
                        )
                    }
                    extension.mcp?.let { mcp ->
                        item { SectionLabel("连接") }
                        item {
                            ListItem(
                                headlineContent = {
                                    Text(mcp.url ?: mcp.command.orEmpty())
                                },
                                supportingContent = {
                                    Text(
                                        if (extension.kind == ExtensionKind.REMOTE_MCP) {
                                            if (mcp.authType == ExtensionAuthType.BEARER) "Bearer" else "无鉴权"
                                        } else {
                                            mcp.args.joinToString(" ").ifBlank { "无参数" }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (extension.tools.isNotEmpty()) {
                        item { SectionLabel("工具") }
                        items(extension.tools, key = ExtensionTool::name) { tool ->
                            ListItem(
                                headlineContent = {
                                    Text(tool.title.ifBlank { tool.name })
                                },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            extensionToolAccessLabel(tool),
                                            tool.name.takeIf { it != tool.title },
                                        ).joinToString(" · "),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = tool.enabled,
                                        onCheckedChange = {
                                            vm.setToolEnabled(extension.id, tool.name, it)
                                        },
                                        enabled = !busy && extension.enabled,
                                    )
                                },
                            )
                        }
                    }
                    item { HorizontalDivider(Modifier.padding(top = AppSpacing.lg)) }
                    item {
                        TextButton(
                            onClick = { deleteConfirmation = true },
                            enabled = !busy,
                            modifier = Modifier.padding(horizontal = AppSpacing.sm),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("删除扩展", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (deleteConfirmation && extension != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除 ${extension.name}") },
            text = { Text("此扩展会从所有会话配置中移除；正在运行的会话会在重新连接后生效。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.delete(extension.id) { result -> if (result.isSuccess) onBack() }
                    },
                    enabled = !busy,
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = false }) { Text("取消") }
            },
        )
    }

    if (remoteEditorVisible && extension?.kind == ExtensionKind.REMOTE_MCP) {
        RemoteMcpEditorDialog(
            initial = extension,
            busy = busy,
            onDismiss = { remoteEditorVisible = false },
            onDiscover = vm::discoverRemote,
            onSave = { name, description, url, auth, token, tools ->
                vm.saveRemote(
                    existingId = extension.id,
                    name = name,
                    description = description,
                    url = url,
                    authType = auth,
                    bearerToken = token,
                    discoveredTools = tools,
                ) { result ->
                    if (result.isSuccess) remoteEditorVisible = false
                }
            },
        )
    }

    if (localEditorVisible && extension?.kind == ExtensionKind.LOCAL_MCP) {
        LocalMcpEditorDialog(
            initial = extension,
            busy = busy,
            onDismiss = { localEditorVisible = false },
            onSave = { name, description, command, args ->
                vm.saveLocal(extension.id, name, description, command, args) { result ->
                    if (result.isSuccess) localEditorVisible = false
                }
            },
        )
    }
}

@Composable
private fun ExtensionLoadError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(AppSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.padding(top = AppSpacing.sm)) {
            Text("重试")
        }
    }
}

@Composable
private fun ExtensionRow(extension: ManagedExtension, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(extension.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                listOf(
                    extensionStatusLabel(extension),
                    extension.tools.takeIf { it.isNotEmpty() }?.let { "${it.size} 个工具" },
                ).filterNotNull().joinToString(" · "),
            )
        },
        leadingContent = { Icon(extensionKindIcon(extension.kind), contentDescription = null) },
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

@Composable
private fun RemoteMcpEditorDialog(
    initial: ManagedExtension? = null,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDiscover: (
        existingId: String?,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: String,
        onResult: (Result<List<ExtensionTool>>) -> Unit,
    ) -> Unit,
    onSave: (
        name: String,
        description: String,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: String,
        tools: List<ExtensionTool>,
    ) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var url by remember(initial?.id) { mutableStateOf(initial?.mcp?.url.orEmpty()) }
    var authType by remember(initial?.id) {
        mutableStateOf(initial?.mcp?.authType ?: ExtensionAuthType.NONE)
    }
    var token by remember(initial?.id) { mutableStateOf("") }
    var tools by remember(initial?.id) { mutableStateOf(initial?.tools.orEmpty()) }
    val canReuseBearer = initial?.mcp?.authType == ExtensionAuthType.BEARER &&
        initial.mcp.url == url

    fun invalidateTools() {
        tools = emptyList()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "添加远程 MCP" else "编辑远程 MCP") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明（可选）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        invalidateTools()
                    },
                    label = { Text("HTTPS 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtensionAuthType.entries.forEach { option ->
                        FilterChip(
                            selected = authType == option,
                            onClick = {
                                authType = option
                                invalidateTools()
                            },
                            label = { Text(if (option == ExtensionAuthType.NONE) "无鉴权" else "Bearer") },
                        )
                    }
                }
                if (authType == ExtensionAuthType.BEARER) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = {
                            token = it
                            invalidateTools()
                        },
                        label = { Text(if (canReuseBearer) "Bearer Token（留空沿用）" else "Bearer Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        onDiscover(initial?.id, url, authType, token) { result ->
                            result.onSuccess { tools = it }
                        }
                    },
                    enabled = !busy && url.isNotBlank() &&
                        (authType == ExtensionAuthType.NONE || token.isNotBlank() || canReuseBearer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在连接" else "发现工具")
                }
                if (tools.isNotEmpty()) {
                    Text("已发现 ${tools.size} 个工具", style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        items(tools, key = ExtensionTool::name) { tool ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        tool.title.ifBlank { tool.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            extensionToolAccessLabel(tool),
                                            tool.name.takeIf { it != tool.title },
                                        ).joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description, url, authType, token, tools) },
                enabled = !busy && name.isNotBlank() && tools.isNotEmpty(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun LocalMcpEditorDialog(
    initial: ManagedExtension? = null,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, List<String>) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var command by remember(initial?.id) {
        mutableStateOf(initial?.mcp?.command ?: "/usr/bin/")
    }
    var args by remember(initial?.id) {
        mutableStateOf(initial?.mcp?.args?.joinToString("\n").orEmpty())
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "添加本地 MCP" else "编辑本地 MCP") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明（可选）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令绝对路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("参数（每行一个）") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name,
                        description,
                        command,
                        args.lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
                    )
                },
                enabled = !busy && name.isNotBlank() && command.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

internal fun extensionKindTitle(kind: ExtensionKind): String = when (kind) {
    ExtensionKind.SKILL -> "Skills"
    ExtensionKind.REMOTE_MCP -> "远程 MCP"
    ExtensionKind.LOCAL_MCP -> "本地 MCP"
}

internal fun extensionToolAccessLabel(tool: ExtensionTool): String =
    if (tool.access == ExtensionToolAccess.READ) {
        "服务声明只读"
    } else {
        "可能写入 · 调用时确认"
    }

private fun extensionKindIcon(kind: ExtensionKind): ImageVector = when (kind) {
    ExtensionKind.SKILL -> Icons.Filled.Description
    ExtensionKind.REMOTE_MCP -> Icons.Filled.Cloud
    ExtensionKind.LOCAL_MCP -> Icons.Filled.Terminal
}

private fun extensionStatusLabel(extension: ManagedExtension): String = when {
    !extension.enabled -> "已停用"
    extension.status == ExtensionStatus.READY -> "已启用"
    extension.status == ExtensionStatus.UNVERIFIED -> "未验证"
    else -> "异常"
}
