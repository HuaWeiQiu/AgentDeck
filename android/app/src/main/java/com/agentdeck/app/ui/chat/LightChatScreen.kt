package com.agentdeck.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.agentdeck.app.domain.model.ConversationIdentity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentdeck.app.data.provider.ChatCompletionsClient
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private data class LightBubble(
    val id: String,
    val role: String,
    val text: String,
)

/**
 * No-PRoot Chat Completions UI.
 * - Smoke: [profileId]/[modelId]/[title] from 模型服务试聊
 * - Session: [cardId] from 对话列表「轻聊」会话（可角色 + 历史落盘）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightChatScreen(
    onBack: () -> Unit,
    profileId: String? = null,
    modelId: String? = null,
    title: String = "轻聊",
    cardId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ServiceLocator.chatCompletions }
    val historyStore = remember { ServiceLocator.piChatHistory }
    val bubbles = remember { mutableStateListOf<LightBubble>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("轻聊 · 无本地 Agent") }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    var binding by remember { mutableStateOf(title) }
    var resolvedProfileId by remember { mutableStateOf(profileId.orEmpty()) }
    var resolvedModelId by remember { mutableStateOf(modelId.orEmpty()) }
    var systemPrompt by remember { mutableStateOf<String?>(null) }
    val historyKey = cardId ?: "smoke-${profileId.orEmpty()}-${modelId.orEmpty()}"

    BackHandler(onBack = onBack)

    LaunchedEffect(cardId, profileId, modelId) {
        if (bubbles.isEmpty()) {
            val restored = withContext(Dispatchers.IO) { historyStore.load(historyKey) }
            restored.forEach { b -> bubbles.add(LightBubble(b.id, b.role, b.text)) }
        }
        if (cardId != null) {
            val card = ServiceLocator.cards.getCard(cardId)
            if (card == null || !com.agentdeck.app.domain.launch.CliAdapterRegistry.usesLightChat(card.recipeId)) {
                status = "轻聊会话不存在"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
            val pid = card.profileId.orEmpty()
            val mid = card.modelId.orEmpty()
            resolvedProfileId = pid
            resolvedModelId = mid
            systemPrompt = lightRoleSystemPrompt(card.identity)
            val profile = ServiceLocator.profiles.getProfile(pid)
            if (profile == null || !profile.adapterId.isChatCompletionsCompatible()) {
                status = "需要 Chat Completions 模型服务"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
            val model = mid.ifBlank { profile.defaultModel }
            resolvedModelId = model
            binding = listOfNotNull(
                card.customTitle ?: card.name,
                profile.name,
                model,
            ).joinToString(" · ")
            status = "就绪 · 轻聊（无 runtime）"
            if (bubbles.none { it.role == "system" }) {
                bubbles.add(
                    LightBubble(
                        id = "sys",
                        role = "system",
                        text = systemPrompt?.takeIf { it.isNotBlank() }
                            ?: "轻聊模式：不启动 Codex / pi，直连 Chat Completions。可在会话编辑里配置角色。",
                    ),
                )
            }
        } else {
            val pid = profileId.orEmpty()
            resolvedProfileId = pid
            val profile = ServiceLocator.profiles.getProfile(pid)
            if (profile == null || !profile.adapterId.isChatCompletionsCompatible()) {
                status = "需要 Chat Completions 模型服务"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
            val model = modelId.orEmpty().ifBlank { profile.defaultModel }
            resolvedModelId = model
            binding = "${profile.name} · $model"
            status = "就绪 · 直连网关（无 PRoot）"
            systemPrompt = null
            if (bubbles.none { it.role == "system" }) {
                bubbles.add(
                    LightBubble(
                        id = "sys",
                        role = "system",
                        text = "轻量试聊不会启动 Codex / pi。适合验证 dots 等 Chat Completions 网关。",
                    ),
                )
            }
        }
    }

    DisposableEffect(historyKey) {
        onDispose {
            val snapshot = bubbles
                .filter { it.role != "system" || it.id != "sys" }
                .map { com.agentdeck.app.data.chat.PiChatHistoryStore.Bubble(it.id, it.role, it.text) }
            Thread {
                runCatching { ServiceLocator.piChatHistory.save(historyKey, snapshot) }
            }.start()
        }
    }

    LaunchedEffect(bubbles.size, bubbles.lastOrNull()?.text?.length) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AgentDeckTopBar(
                title = {
                    Column {
                        Text(
                            if (cardId != null) "轻聊" else "轻量试聊",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            binding,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            streamJob?.cancel()
                            onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            LightChatBottomBar(
                status = status,
                input = input,
                onInputChange = { input = it },
                busy = busy,
                onSendOrStop = {
                    if (busy) {
                        streamJob?.cancel()
                        streamJob = null
                        busy = false
                        status = "已停止"
                    } else {
                        streamJob = lightChatSend(
                            scope = scope,
                            client = client,
                            profileId = resolvedProfileId,
                            modelId = resolvedModelId,
                            text = input,
                            bubbles = bubbles,
                            systemPrompt = systemPrompt,
                            onInputClear = { input = "" },
                            onBusy = { busy = it },
                            onStatus = { status = it },
                            onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                            onJob = { streamJob = it },
                            onPersist = {
                                val snapshot = bubbles
                                    .filter { b -> b.role != "system" || b.id != "sys" }
                                    .map { b ->
                                        com.agentdeck.app.data.chat.PiChatHistoryStore.Bubble(
                                            b.id, b.role, b.text,
                                        )
                                    }
                                scope.launch(Dispatchers.IO) {
                                    historyStore.save(historyKey, snapshot)
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(bubbles, key = { it.id }) { bubble ->
                when (bubble.role) {
                    "user" -> BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.widthIn(max = maxWidth * 0.88f),
                        ) {
                            Text(
                                bubble.text,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    "error" -> Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            bubble.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    "system" -> Text(
                        bubble.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        bubble.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun LightChatBottomBar(
    status: String,
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    onSendOrStop: () -> Unit,
) {
    Column(modifier = Modifier.imePadding()) {
        HorizontalDivider()
        Text(
            status,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatComposerTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                placeholder = if (busy) "生成中…" else "发消息（无工具）",
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSendOrStop,
                enabled = if (busy) true else input.isNotBlank(),
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    if (busy) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (busy) "停止" else "发送",
                )
            }
        }
    }
}

private fun lightRoleSystemPrompt(identity: ConversationIdentity?): String? {
    if (identity == null) return null
    val parts = buildList {
        if (identity.roleName.isNotBlank()) add("你是「${identity.roleName}」。")
        if (identity.selfDefinition.isNotBlank()) add(identity.selfDefinition)
        if (identity.objective.isNotBlank()) add("目标：${identity.objective}")
        if (identity.communicationStyle.isNotBlank()) add("沟通风格：${identity.communicationStyle}")
        if (identity.boundaries.isNotBlank()) add("边界：${identity.boundaries}")
    }
    return parts.joinToString("\n").ifBlank { null }
}

private fun lightChatSend(
    scope: CoroutineScope,
    client: ChatCompletionsClient,
    profileId: String,
    modelId: String,
    text: String,
    bubbles: SnapshotStateList<LightBubble>,
    systemPrompt: String?,
    onInputClear: () -> Unit,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onToast: (String) -> Unit,
    onJob: (Job?) -> Unit,
    onPersist: () -> Unit = {},
): Job? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    onInputClear()
    bubbles.add(LightBubble("u-" + System.currentTimeMillis(), "user", trimmed))
    val assistantId = "a-" + System.currentTimeMillis()
    bubbles.add(LightBubble(assistantId, "assistant", ""))
    onBusy(true)
    onStatus("生成中…")
    val history = buildList {
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            add(ChatCompletionsClient.Message(role = "system", content = it))
        }
        bubbles
            .filter { b -> b.role == "user" || (b.role == "assistant" && b.id != assistantId) }
            .forEach { b ->
                add(
                    ChatCompletionsClient.Message(
                        role = if (b.role == "user") "user" else "assistant",
                        content = b.text,
                    ),
                )
            }
    }
    return scope.launch {
        try {
            val profile = ServiceLocator.profiles.getProfile(profileId)
            if (profile == null || !profile.adapterId.isChatCompletionsCompatible()) {
                onToast("模型服务不可用")
                onBusy(false)
                return@launch
            }
            val model = modelId.ifBlank { profile.defaultModel }
            client.stream(profile, model, history)
                .catch { e ->
                    val idx = bubbles.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) {
                        bubbles[idx] = bubbles[idx].copy(
                            text = (bubbles[idx].text + "\n[错误] " + (e.message ?: "失败")).trim(),
                            role = "error",
                        )
                    }
                    onStatus(e.message ?: "失败")
                }
                .collect { event ->
                    when (event) {
                        is ChatCompletionsClient.Event.Delta -> {
                            val idx = bubbles.indexOfFirst { it.id == assistantId }
                            if (idx >= 0) {
                                bubbles[idx] = bubbles[idx].copy(
                                    text = bubbles[idx].text + event.text,
                                )
                            }
                        }
                        is ChatCompletionsClient.Event.Completed -> {
                            val idx = bubbles.indexOfFirst { it.id == assistantId }
                            if (idx >= 0 && event.fullText.isNotBlank()) {
                                bubbles[idx] = bubbles[idx].copy(text = event.fullText)
                            }
                            onStatus("就绪 · 直连网关（无 PRoot）")
                        }
                        is ChatCompletionsClient.Event.Failed -> {
                            val idx = bubbles.indexOfFirst { it.id == assistantId }
                            if (idx >= 0) {
                                bubbles[idx] = bubbles[idx].copy(
                                    text = event.message,
                                    role = "error",
                                )
                            }
                            onStatus(event.message)
                        }
                    }
                }
        } finally {
            onBusy(false)
            onJob(null)
            onPersist()
        }
    }.also(onJob)
}
