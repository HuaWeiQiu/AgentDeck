package com.agentdeck.app.data.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agentdeck.app.MainActivity
import com.agentdeck.app.R
import com.agentdeck.app.data.secure.ProviderCredentialBroker
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.chat.ChatApproval
import com.agentdeck.app.domain.chat.ChatUserInputRequest
import com.agentdeck.app.domain.chat.CodexModelOption
import com.agentdeck.app.domain.chat.QueuedChatMessage
import com.agentdeck.app.domain.model.CodexPermissionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps a Codex chat session alive after the chat screen closes, so long turns keep
 * running in the background. While held, the registry drains the event stream:
 * completed items are buffered for replay on reattach, pending server requests
 * (approvals / user-input) are kept for redelivery, and turn completion or new
 * approval requests raise system notifications that deep-link back to the chat.
 *
 * Completed background sessions are released after a short grace period. A queued
 * user message keeps the session available until the user returns.
 */
object ChatSessionRegistry {
    class HeldSession internal constructor(
        val cardId: String,
        val client: CodexRpcClient,
        val endpoint: CodexBridgeEndpoint,
        val threadId: String,
        val permissionLevel: CodexPermissionLevel,
        val runtimeModel: String?,
        val runtimeProvider: String?,
        val availableModels: List<CodexModelOption>,
        val selectedModel: String?,
        val selectedPermission: CodexPermissionLevel?,
        val reasoningEffort: String?,
        val developerInstructions: String?,
        @Volatile var activeTurnId: String?,
        @Volatile var isBusy: Boolean,
        @Volatile var pendingApproval: ChatApproval?,
        @Volatile var pendingUserInput: ChatUserInputRequest?,
        val queued: QueuedChatMessage?,
        /** Managed-provider credential broker; must stay alive with the session. */
        val broker: ProviderCredentialBroker?,
        /** Raw `item` payloads completed while detached, replayed on reattach. */
        val bufferedItems: MutableList<JSONObject>,
        /** Raw turn payloads from turn/completed|failed|cancelled while detached. */
        val bufferedTurns: MutableList<JSONObject>,
        /** Server requests (approval / requestUserInput) that arrived while detached. */
        val pendingRequests: MutableList<CodexInbound.ServerRequest>,
        internal val scope: CoroutineScope,
        internal var collector: Job? = null,
    )

    private val sessions = ConcurrentHashMap<String, HeldSession>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureChannel()
    }

    /** Hand a live session over to the registry; returns false when one is already held. */
    fun hold(
        cardId: String,
        cardName: String,
        client: CodexRpcClient,
        endpoint: CodexBridgeEndpoint,
        threadId: String,
        permissionLevel: CodexPermissionLevel,
        runtimeModel: String?,
        runtimeProvider: String?,
        availableModels: List<CodexModelOption>,
        selectedModel: String?,
        selectedPermission: CodexPermissionLevel?,
        reasoningEffort: String?,
        developerInstructions: String?,
        activeTurnId: String?,
        pendingApproval: ChatApproval?,
        pendingUserInput: ChatUserInputRequest?,
        queued: QueuedChatMessage?,
        broker: ProviderCredentialBroker?,
    ): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = HeldSession(
            cardId = cardId,
            client = client,
            endpoint = endpoint,
            threadId = threadId,
            permissionLevel = permissionLevel,
            runtimeModel = runtimeModel,
            runtimeProvider = runtimeProvider,
            availableModels = availableModels,
            selectedModel = selectedModel,
            selectedPermission = selectedPermission,
            reasoningEffort = reasoningEffort,
            developerInstructions = developerInstructions,
            activeTurnId = activeTurnId,
            isBusy = true,
            pendingApproval = pendingApproval,
            pendingUserInput = pendingUserInput,
            queued = queued,
            broker = broker,
            bufferedItems = CopyOnWriteArrayList(),
            bufferedTurns = CopyOnWriteArrayList(),
            pendingRequests = CopyOnWriteArrayList(),
            scope = scope,
        )
        if (sessions.putIfAbsent(cardId, session) != null) {
            scope.cancel()
            return false
        }
        session.collector = scope.launch {
            try {
                client.events.collect { event -> handleEvent(session, cardName, event) }
            } catch (error: Exception) {
                teardown(session, "连接中断：${error.message ?: "未知错误"}")
            }
        }
        return true
    }

    /** Take the session back for interactive use; stops background draining. */
    fun take(cardId: String): HeldSession? = sessions.remove(cardId)?.also { session ->
        session.collector?.cancel()
        session.collector = null
        session.scope.cancel()
    }

    /** Explicit teardown requested by the user; stops the bridge process. */
    fun stopAndRemove(cardId: String) {
        val session = sessions.remove(cardId) ?: return
        session.collector?.cancel()
        session.scope.cancel()
        stopBridge(session)
    }

    fun isHeld(cardId: String): Boolean = sessions.containsKey(cardId)

    /** Release a completed held session before changing its launch configuration. */
    fun releaseIfIdle(cardId: String): Boolean {
        val session = sessions[cardId] ?: return true
        if (session.isBusy || session.queued != null || session.pendingApproval != null ||
            session.pendingUserInput != null || session.pendingRequests.isNotEmpty()
        ) {
            return false
        }
        if (!sessions.remove(cardId, session)) return false
        session.collector?.cancel()
        session.scope.cancel()
        stopBridge(session)
        return true
    }

    private suspend fun handleEvent(
        session: HeldSession,
        cardName: String,
        event: CodexInbound,
    ) {
        when (event) {
            is CodexInbound.Notification -> when (event.method) {
                "turn/started" -> {
                    session.activeTurnId = event.params.optJSONObject("turn")
                        ?.optString("id")
                        ?.takeIf(String::isNotBlank)
                    session.isBusy = true
                }

                "item/completed" -> event.params.optJSONObject("item")?.let { item ->
                    session.bufferedItems.add(item)
                }

                "turn/completed" -> {
                    session.activeTurnId = null
                    session.isBusy = false
                    session.pendingApproval = null
                    session.pendingUserInput = null
                    session.pendingRequests.clear()
                    event.params.optJSONObject("turn")?.let(session.bufferedTurns::add)
                    runCatching { ServiceLocator.cards.touchActivity(session.cardId) }
                    postNotification(
                        cardId = session.cardId,
                        title = "$cardName · Codex 已完成任务",
                        text = "点按回到对话查看结果",
                    )
                    scheduleIdleTeardown(session)
                }

                "turn/failed", "turn/cancelled" -> {
                    session.activeTurnId = null
                    session.isBusy = false
                    session.pendingApproval = null
                    session.pendingUserInput = null
                    session.pendingRequests.clear()
                    event.params.optJSONObject("turn")?.let(session.bufferedTurns::add)
                    runCatching { ServiceLocator.cards.touchActivity(session.cardId) }
                    postNotification(
                        cardId = session.cardId,
                        title = "$cardName · Codex 任务已结束",
                        text = "点按回到对话查看详情",
                    )
                    scheduleIdleTeardown(session)
                }

                "error" -> postNotification(
                    cardId = session.cardId,
                    title = "$cardName · Codex 报告了错误",
                    text = "点按回到对话查看详情",
                )

                // Streaming deltas and progress chatter are dropped while detached;
                // authoritative items arrive via item/completed and turn/completed.
                else -> Unit
            }

            is CodexInbound.ServerRequest -> {
                session.pendingRequests.add(event)
                event.params.optString("turnId").takeIf(String::isNotBlank)?.let {
                    session.activeTurnId = it
                }
                session.isBusy = true
                val text = when (event.method) {
                    "item/tool/requestUserInput" -> "Codex 有问题需要你回答"
                    else -> "Codex 的操作等待你的确认"
                }
                postNotification(
                    cardId = session.cardId,
                    title = "$cardName · 需要你的确认",
                    text = text,
                )
            }

            is CodexInbound.Disconnected ->
                teardown(session, "Codex 连接已断开：${event.message}")
        }
    }

    private fun teardown(session: HeldSession, reason: String) {
        if (sessions.remove(session.cardId, session)) {
            postNotification(
                cardId = session.cardId,
                title = "Codex 后台任务已停止",
                text = reason,
            )
            stopBridge(session)
            session.scope.cancel()
        }
    }

    private fun scheduleIdleTeardown(session: HeldSession) {
        if (session.queued != null) return
        session.scope.launch {
            delay(IDLE_TEARDOWN_DELAY_MILLIS)
            if (!session.isBusy && session.pendingApproval == null &&
                session.pendingUserInput == null &&
                session.pendingRequests.isEmpty() && sessions.remove(session.cardId, session)
            ) {
                stopBridge(session)
                session.scope.cancel()
            }
        }
    }

    private fun stopBridge(session: HeldSession) {
        session.client.close()
        session.broker?.close()
        // The session scope may already be cancelled; bridge teardown runs on a
        // short-lived IO scope that cancels itself when done.
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        teardownScope.launch {
            try {
                runCatching { ServiceLocator.codexBridge.stop(session.endpoint) }
            } finally {
                teardownScope.cancel()
            }
        }
    }

    private fun postNotification(cardId: String, title: String, text: String) {
        val context = appContext ?: return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEEP_LINK_CARD_ID, cardId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            cardId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(NOTIFICATION_ID_BASE + cardId.hashCode() and 0xFFFF, notification)
    }

    private fun ensureChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Codex 后台任务",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }
    }

    private const val CHANNEL_ID = "agentdeck_chat_events"
    private const val NOTIFICATION_ID_BASE = 5_000
    private const val IDLE_TEARDOWN_DELAY_MILLIS = 30_000L
}
