package com.agentdeck.app.domain.host

/**
 * 真实目录写操作的持久审批偏好（高级设置）。
 * 「本会话允许」仅存在于当前聊天内存，不写入此枚举。
 */
enum class HostWriteApprovalMode {
    /** 每次写/删/push 都弹窗（默认） */
    ALWAYS_ASK,

    /** 开启工作区后不再弹窗（仍受路径/授权约束） */
    NEVER_ASK,
    ;

    companion object {
        fun fromStorage(value: String?): HostWriteApprovalMode =
            entries.firstOrNull { it.name == value } ?: ALWAYS_ASK
    }
}
