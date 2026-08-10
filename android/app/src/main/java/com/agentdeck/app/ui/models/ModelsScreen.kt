package com.agentdeck.app.ui.models

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    vm: ModelsViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val accountState by vm.accountState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ProviderEditorDraft?>(null) }
    var deleting by remember { mutableStateOf<ProviderProfile?>(null) }
    var apiKeyDialogVisible by remember { mutableStateOf(false) }

    LaunchedEffect(accountState.account) {
        if (accountState.account != null) apiKeyDialogVisible = false
    }

    val activeEditor = editor
    if (activeEditor != null) {
        // 编辑器是 early-return 假导航，拦截系统返回键关闭编辑器而不是退出页面
        BackHandler { editor = null }
        ProviderEditorScreen(
            initial = activeEditor,
            onBack = { editor = null },
            onDiscover = vm::discover,
            onSave = vm::save,
            onSaved = {
                editor = null
                Toast.makeText(context, "模型服务已保存", Toast.LENGTH_SHORT).show()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editor = vm.newDraft() }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加模型服务")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                SectionLabel("Codex 账号")
            }
            if (accountState.isLoading) {
                item {
                    ListItem(
                        headlineContent = { Text("正在读取账号状态") },
                        leadingContent = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
                    )
                }
            } else if (accountState.account != null) {
                item {
                    val account = requireNotNull(accountState.account)
                    ListItem(
                        headlineContent = {
                            Text(
                                when (account.type) {
                                    com.agentdeck.app.data.chat.CodexAccountType.CHATGPT -> "ChatGPT 已登录"
                                    com.agentdeck.app.data.chat.CodexAccountType.API_KEY -> "OpenAI API Key 已连接"
                                    com.agentdeck.app.data.chat.CodexAccountType.OTHER -> "Codex 账号已连接"
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(account.email, account.planType?.uppercase())
                                    .joinToString(" · ")
                                    .ifBlank { "凭据由 Codex 保存在内嵌运行环境" },
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            if (accountState.isWorking) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = vm::logout) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "退出 Codex 账号",
                                    )
                                }
                            }
                        },
                    )
                }
            } else {
                item {
                    ListItem(
                        headlineContent = { Text("使用 ChatGPT 账号") },
                        supportingContent = { Text("推荐 · 使用 ChatGPT 套餐，通过设备码安全登录") },
                        leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                        trailingContent = {
                            if (accountState.isWorking) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.ChevronRight, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable(
                            enabled = !accountState.isWorking,
                            onClick = vm::startChatGptLogin,
                        ),
                    )
                }
                item { HorizontalDivider() }
                item {
                    ListItem(
                        headlineContent = { Text("使用 OpenAI API Key") },
                        supportingContent = { Text("按 API 用量计费 · 由 Codex 在内嵌环境中持久化授权") },
                        leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(
                            enabled = !accountState.isWorking,
                            onClick = { apiKeyDialogVisible = true },
                        ),
                    )
                }
            }
            accountState.error?.let { error ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::refreshAccount) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重试读取账号")
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                SectionLabel("第三方 Responses 服务")
            }
            if (profiles.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.md))
                        Text(
                            "尚未添加第三方模型服务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            "Sub2API 是预设；其他服务需兼容 OpenAI Responses，支持 /v1/models 时会自动读取模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.lg))
                        Button(onClick = { editor = vm.newDraft() }) {
                            Text("添加模型服务")
                        }
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProviderRow(
                    profile = profile,
                    onEdit = { editor = vm.editDraft(profile) },
                    onDelete = { deleting = profile },
                )
                HorizontalDivider()
            }
        }
    }

    accountState.deviceLogin?.let { login ->
        ChatGptDeviceLoginDialog(
            login = login,
            onOpen = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ChatGPT device code", login.userCode)
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipboard.setPrimaryClip(clip)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.verificationUrl)))
                }.onFailure {
                    Toast.makeText(context, "无法打开浏览器，登录代码已复制", Toast.LENGTH_LONG).show()
                }
            },
            onCancel = vm::cancelChatGptLogin,
        )
    }

    if (apiKeyDialogVisible) {
        ApiKeyLoginDialog(
            working = accountState.isWorking,
            onLogin = { apiKey ->
                apiKeyDialogVisible = false
                vm.loginWithApiKey(apiKey)
            },
            onDismiss = { apiKeyDialogVisible = false },
        )
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${profile.name}") },
            text = { Text("已被对话使用的服务不能删除。删除后，加密 API Key 和模型缓存也会移除。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            vm.delete(profile).fold(
                                onSuccess = {
                                    deleting = null
                                    Toast.makeText(context, "模型服务已删除", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "删除失败",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
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
private fun ChatGptDeviceLoginDialog(
    login: com.agentdeck.app.data.chat.CodexDeviceLogin,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("登录 ChatGPT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在浏览器中输入以下代码。授权完成后，此页面会自动更新。")
                SelectionContainer {
                    Text(login.userCode, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    login.verificationUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("等待授权", modifier = Modifier.padding(start = 10.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("复制代码并打开")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun ApiKeyLoginDialog(
    working: Boolean,
    onLogin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            apiKey = ""
            if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("连接 OpenAI API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("API Key 交给 Codex 官方登录流程保存，不会写入 config.toml。")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenAI API Key") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val submitted = apiKey
                    apiKey = ""
                    onLogin(submitted)
                },
                enabled = apiKey.isNotBlank() && !working,
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("取消") }
        },
    )
}

@Composable
private fun ProviderRow(
    profile: ProviderProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    profile.baseUrl,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (profile.adapterId == ProviderAdapterId.SUB2API) {
                        "Sub2API 预设"
                    } else {
                        "Responses"
                    }) + " · ${profile.defaultModel}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            val ready = profile.connectionStatus == ProviderConnectionStatus.READY ||
                profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED
            Icon(
                if (ready) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            // 整行 clickable 已进入编辑，仅保留删除按钮，避免重复焦点
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除 ${profile.name}")
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorScreen(
    initial: ProviderEditorDraft,
    onBack: () -> Unit,
    onDiscover: suspend (ProviderEditorDraft) -> ProviderEditorDraft,
    onSave: suspend (ProviderEditorDraft) -> Result<ProviderProfile>,
    onSaved: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var keyVisible by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "添加模型服务" else "编辑模型服务") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !working) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.SUB2API,
                        onClick = {
                            draft = draft.selectAdapter(ProviderAdapterId.SUB2API)
                        },
                        label = { Text("Sub2API 预设") },
                    )
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.OPENAI_RESPONSES,
                        onClick = {
                            draft = draft.selectAdapter(ProviderAdapterId.OPENAI_RESPONSES)
                        },
                        label = { Text("其他 Responses 服务") },
                    )
                }
            }
            item {
                Text(
                    if (draft.adapterId == ProviderAdapterId.SUB2API) {
                        "适用于 Sub2API 部署；按 Responses 标准连接，并自动从 /v1/models 读取模型。"
                    } else {
                        "适用于 OpenAI Responses 兼容服务；没有 /v1/models 时，验证后可手填模型 ID。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it, error = null) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = {
                        draft = draft.copy(
                            baseUrl = it,
                            validated = false,
                            models = emptyList(),
                            error = null,
                        )
                    },
                    label = { Text("API Base URL") },
                    placeholder = {
                        Text(
                            if (draft.adapterId == ProviderAdapterId.SUB2API) {
                                "https://你的-sub2api-域名/v1"
                            } else {
                                "https://api.example.com/v1"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = {
                        draft = draft.copy(apiKey = it, validated = false, error = null)
                    },
                    label = {
                        Text(if (draft.hasStoredCredential) "API Key（留空沿用）" else "API Key")
                    },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            working = true
                            draft = onDiscover(draft)
                            working = false
                        }
                    },
                    enabled = !working && draft.name.isNotBlank() && draft.baseUrl.isNotBlank() &&
                        (draft.apiKey.isNotBlank() || draft.hasStoredCredential),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Text(if (working) "正在验证" else "验证并获取模型")
                }
            }
            draft.error?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                if (draft.models.isNotEmpty()) {
                    ModelDropdown(
                        selected = draft.model,
                        models = draft.models,
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                        onQueryChange = {
                            draft = draft.copy(model = "", error = null)
                        },
                        onSelect = { model ->
                            draft = draft.copy(model = model.id, error = null)
                            modelExpanded = false
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it, error = null) },
                        label = { Text("模型 ID") },
                        singleLine = true,
                        enabled = draft.status == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = {
                        scope.launch {
                            working = true
                            onSave(draft).fold(
                                onSuccess = {
                                    draft = draft.copy(apiKey = "")
                                    onSaved()
                                },
                                onFailure = { error ->
                                    draft = draft.copy(error = error.message ?: "保存失败")
                                    working = false
                                },
                            )
                        }
                    },
                    enabled = !working && draft.validated && draft.model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存模型服务")
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selected: String,
    models: List<ProviderModel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onQueryChange: () -> Unit,
    onSelect: (ProviderModel) -> Unit,
) {
    var query by remember(models) { mutableStateOf(selected) }
    val filtered = remember(models, query) {
        models.filter { model ->
            query.isBlank() || model.id.contains(query, ignoreCase = true) ||
                model.displayName.contains(query, ignoreCase = true)
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { next ->
            if (!next) query = selected
            onExpandedChange(next)
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange()
                onExpandedChange(true)
            },
            label = { Text("默认模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            filtered.take(MAX_VISIBLE_MODELS).forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (model.displayName != model.id) {
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        query = model.id
                        onSelect(model)
                    },
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val MAX_VISIBLE_MODELS = 100
