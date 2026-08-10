package com.agentdeck.app.domain.chat

import androidx.compose.runtime.Immutable
import com.agentdeck.app.data.chat.RpcRequestId
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel

enum class ChatItemKind {
    USER,
    ASSISTANT,
    REASONING,
    COMMAND,
    FILE_CHANGE,
    TOOL,
    ERROR,
}

@Immutable
data class FilePatch(
    val path: String,
    /** Patch change kind from the protocol: add / delete / update. */
    val kind: String,
    val diff: String,
)

@Immutable
data class ChatItem(
    val id: String,
    val kind: ChatItemKind,
    val text: String,
    val detail: String? = null,
    val status: String? = null,
    val patches: List<FilePatch> = emptyList(),
    val turnId: String? = null,
)

@Immutable
data class CodexRuntime(
    val model: String,
    val provider: String,
)

@Immutable
data class CodexModelOption(
    val id: String,
    val displayName: String = id,
    val isDefault: Boolean = false,
)

@Immutable
data class CodexModelPage(
    val models: List<CodexModelOption>,
    val nextCursor: String?,
)

enum class ApprovalKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
}

@Immutable
data class ChatApproval(
    val requestId: RpcRequestId,
    val kind: ApprovalKind,
    val title: String,
    val detail: String,
    val requestedPermissions: String? = null,
    /** Item that triggered the approval; used to attach live patch data for previews. */
    val itemId: String? = null,
)

@Immutable
data class ToolUserInputOption(
    val label: String,
    val description: String,
)

@Immutable
data class ToolUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<ToolUserInputOption> = emptyList(),
    /** When true the user may answer with free text instead of a listed option. */
    val isOther: Boolean = false,
    /** When true the answer is sensitive and must be masked in the UI. */
    val isSecret: Boolean = false,
)

/**
 * A pending `item/tool/requestUserInput` server request. Mirrors the approval
 * slot semantics: one at a time, latest request replaces the previous one, and
 * the slot clears on disconnect.
 */
@Immutable
data class ChatUserInputRequest(
    val requestId: RpcRequestId,
    val itemId: String,
    val questions: List<ToolUserInputQuestion>,
)

/** A message composed while a turn/approval was in flight, to be sent as a new turn. */
@Immutable
data class QueuedChatMessage(
    val id: String,
    val text: String,
)

/**
 * Errors surfaced by the chat pipeline, classified in the ViewModel layer so the
 * UI only renders them. [raw] keeps the original message for technical detail mode.
 */
@Immutable
sealed interface ChatError {
    val raw: String

    @Immutable
    data class Auth(override val raw: String) : ChatError

    @Immutable
    data class Network(override val raw: String) : ChatError

    @Immutable
    data class Model(override val raw: String) : ChatError

    @Immutable
    data class Unknown(override val raw: String) : ChatError

    companion object {
        fun from(raw: String): ChatError {
            val normalized = raw.lowercase()
            return when {
                "登录" in raw || "auth" in normalized || "unauthorized" in normalized ->
                    Auth(raw)
                "模型" in raw || "provider" in normalized || "model" in normalized ->
                    Model(raw)
                "连接" in raw || "connect" in normalized || "timeout" in normalized ->
                    Network(raw)
                else -> Unknown(raw)
            }
        }
    }
}

@Immutable
data class ChatUiState(
    val card: AgentCard? = null,
    val isConnecting: Boolean = true,
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val isReconnecting: Boolean = false,
    val runtimeModel: String? = null,
    val runtimeProvider: String? = null,
    val items: List<ChatItem> = emptyList(),
    val streamingItemId: String? = null,
    val composer: String = "",
    val approval: ChatApproval? = null,
    /** Pending `item/tool/requestUserInput` server request, if any. */
    val userInputRequest: ChatUserInputRequest? = null,
    /** Message typed while a turn was running and steering failed; sent on turn end. */
    val queued: QueuedChatMessage? = null,
    /** Formatted token usage of the last completed turn (technical detail mode only). */
    val lastTurnTokens: String? = null,
    /** Models offered by the bound provider for in-chat override (advanced mode). */
    val availableModels: List<CodexModelOption> = emptyList(),
    /** Per-chat model override; null uses the card-bound model. */
    val selectedModel: String? = null,
    /** Per-chat permission override; null inherits the effective default. */
    val selectedPermission: CodexPermissionLevel? = null,
    val error: ChatError? = null,
) {
    // Computed once at construction; copy() re-runs the constructor so this always
    // reflects the current field values. Sending while streaming steers the active
    // turn, so the composer only blocks on approval/user-input slots and disconnect.
    val canSend: Boolean =
        isConnected && !isConnecting &&
            composer.isNotBlank() && approval == null && userInputRequest == null
}
