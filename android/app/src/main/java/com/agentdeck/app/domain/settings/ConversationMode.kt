package com.agentdeck.app.domain.settings

/**
 * Product conversation mode (Secure):
 * - [LIGHT] 轻聊：无本地 Agent runtime，Chat Completions + 角色
 * - [DEV] 开发：Codex / pi / dsh 等完整工具链
 *
 * Lab flavor remains the separate “狂暴” surface — not a value here.
 */
enum class ConversationMode {
    LIGHT,
    DEV,
    ;

    val title: String
        get() = when (this) {
            LIGHT -> "轻聊"
            DEV -> "开发"
        }

    val summary: String
        get() = when (this) {
            LIGHT -> "不启动 Codex / pi，直连模型服务，可写角色"
            DEV -> "使用本地 Agent runtime（Codex / pi / dsh）"
        }

    companion object {
        fun fromStorage(value: String?): ConversationMode = entries
            .firstOrNull { it.name == value }
            ?: LIGHT
    }
}
