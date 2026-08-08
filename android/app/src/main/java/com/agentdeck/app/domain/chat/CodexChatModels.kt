package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId
import com.agentdeck.app.domain.model.AgentCard

enum class ChatItemKind {
    USER,
    ASSISTANT,
    REASONING,
    COMMAND,
    FILE_CHANGE,
    TOOL,
    ERROR,
}

data class ChatItem(
    val id: String,
    val kind: ChatItemKind,
    val text: String,
    val detail: String? = null,
    val status: String? = null,
)

enum class ApprovalKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
}

data class ChatApproval(
    val requestId: RpcRequestId,
    val kind: ApprovalKind,
    val title: String,
    val detail: String,
    val requestedPermissions: String? = null,
)

data class ChatUiState(
    val card: AgentCard? = null,
    val isConnecting: Boolean = true,
    val isStreaming: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val composer: String = "",
    val approval: ChatApproval? = null,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = !isConnecting && !isStreaming && composer.isNotBlank() && approval == null
}
