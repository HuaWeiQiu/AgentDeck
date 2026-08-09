package com.agentdeck.app.domain.model

enum class CodexPermissionLevel {
    READ_ONLY,
    ASK_FIRST,
    FULL_ACCESS,
    ;

    val approvalPolicy: String
        get() = when (this) {
            READ_ONLY, ASK_FIRST -> "untrusted"
            FULL_ACCESS -> "never"
        }

    val terminalApprovalPolicy: String?
        get() = when (this) {
            READ_ONLY -> null
            ASK_FIRST -> "untrusted"
            FULL_ACCESS -> "never"
        }

    companion object {
        val DEFAULT = ASK_FIRST

        fun fromStorage(value: String?): CodexPermissionLevel =
            entries.firstOrNull { it.name == value } ?: DEFAULT

        fun overrideFromStorage(value: String?): CodexPermissionLevel? =
            entries.firstOrNull { it.name == value }

        fun effective(
            override: CodexPermissionLevel?,
            default: CodexPermissionLevel,
        ): CodexPermissionLevel = override ?: default

    }
}
