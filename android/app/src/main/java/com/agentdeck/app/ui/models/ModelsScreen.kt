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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.material3.Surface
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
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import com.agentdeck.app.domain.settings.ConversationMode
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.ui.common.DEFAULT_MAX_VISIBLE_MODELS
import com.agentdeck.app.ui.common.filterSelectableModels
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import com.agentdeck.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    onOpenLightChat: (profileId: String, modelId: String, title: String) -> Unit = { _, _, _ -> },
    vm: ModelsViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val accountState by vm.accountState.collectAsStateWithLifecycle()
    val conversationMode by ServiceLocator.experienceSettings.conversationMode
        .collectAsStateWithLifecycle()
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
            AgentDeckTopBar(
                title = "模型服务",
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
                SectionLabel("第三方模型服务")
            }
            item {
                Text(
                    when (conversationMode) {
                        ConversationMode.LIGHT ->
                            "轻聊主要用 Chat Completions（如 dots）。Responses 可先备着，切到开发模式给 Codex 用。"
                        ConversationMode.DEV ->
                            "Responses 给 Codex 原生聊天；Chat Completions 给 pi 与轻聊（如小红书 dots）。"
                    },
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = AppSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                            when (conversationMode) {
                                ConversationMode.LIGHT ->
                                    "先添加 Chat Completions 即可开始轻聊。"
                                ConversationMode.DEV ->
                                    "Responses 给 Codex；Chat Completions 给 pi / 轻聊。"
                            },
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
                    onLightChat = if (profile.adapterId.isChatCompletionsCompatible()) {
                        {
                            onOpenLightChat(
                                profile.id,
                                profile.defaultModel,
                                profile.name,
                            )
                        }
                    } else {
                        null
                    },
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
    onLightChat: (() -> Unit)? = null,
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
                    when (profile.adapterId) {
                        ProviderAdapterId.SUB2API -> "Sub2API · Codex"
                        ProviderAdapterId.OPENAI_CHAT_COMPLETIONS -> "Chat · pi/dsh · 可轻量试聊"
                        else -> "Responses · Codex"
                    } + " · ${profile.defaultModel}",
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
            Row {
                if (onLightChat != null) {
                    IconButton(onClick = onLightChat) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "轻量试聊 ${profile.name}",
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除 ${profile.name}")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            AgentDeckTopBar(
                title = if (draft.id == null) "添加模型服务" else "编辑模型服务",
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "协议类型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.SUB2API,
                        onClick = {
                            draft = draft.selectAdapter(ProviderAdapterId.SUB2API)
                        },
                        label = { Text("Sub2API") },
                    )
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.OPENAI_RESPONSES,
                        onClick = {
                            draft = draft.selectAdapter(ProviderAdapterId.OPENAI_RESPONSES)
                        },
                        label = { Text("Responses") },
                    )
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.OPENAI_CHAT_COMPLETIONS,
                        onClick = {
                            draft = draft.selectAdapter(ProviderAdapterId.OPENAI_CHAT_COMPLETIONS)
                        },
                        label = { Text("Chat") },
                    )
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            when (draft.adapterId) {
                                ProviderAdapterId.OPENAI_CHAT_COMPLETIONS ->
                                    "Chat Completions · 给 pi / dsh"
                                ProviderAdapterId.SUB2API ->
                                    "Sub2API · 给 Codex 原生聊天"
                                else ->
                                    "Responses · 给 Codex 原生聊天"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when (draft.adapterId) {
                                ProviderAdapterId.SUB2API ->
                                    "需要 /v1/responses。若上游只有 chat/completions，请改选 Chat。"
                                ProviderAdapterId.OPENAI_CHAT_COMPLETIONS ->
                                    "POST /v1/chat/completions（小红书 dots 等）。会话里选 pi 并绑定此服务。"
                                else ->
                                    "需要 OpenAI Responses（POST /v1/responses）。chat 网关请改选 Chat。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
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
                            when (draft.adapterId) {
                                ProviderAdapterId.SUB2API -> "https://你的-sub2api-域名/v1"
                                ProviderAdapterId.OPENAI_CHAT_COMPLETIONS ->
                                    "https://note3-prev-api.askdiandian.com/v1"
                                else -> "https://api.example.com/v1"
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
                    // 已拉取到模型列表：只能从列表选择，不允许手改 ID。
                    ModelDropdown(
                        selected = draft.model,
                        models = draft.models,
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                        onSelect = { model ->
                            draft = draft.copy(model = model.id, error = null)
                            modelExpanded = false
                        },
                    )
                } else {
                    // 仅当上游不支持 /models 时，允许手填模型 ID。
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it, error = null) },
                        label = { Text("模型 ID") },
                        singleLine = true,
                        enabled = draft.status == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
                        supportingText = if (draft.status == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED) {
                            { Text("该服务不支持自动发现，请填写模型 ID") }
                        } else {
                            null
                        },
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
    onSelect: (ProviderModel) -> Unit,
) {
    val options = remember(models) {
        filterSelectableModels(
            models = models,
            query = "",
            selectedId = null,
            maxVisible = DEFAULT_MAX_VISIBLE_MODELS,
        )
    }
    val selectedLabel = models.firstOrNull { it.id == selected }?.let { model ->
        if (model.displayName != model.id) "${model.displayName}（${model.id}）" else model.id
    } ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("默认模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { model ->
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
                    onClick = { onSelect(model) },
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
