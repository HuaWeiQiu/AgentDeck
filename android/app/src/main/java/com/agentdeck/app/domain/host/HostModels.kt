package com.agentdeck.app.domain.host

/**
 * Android 宿主能力分级（ADR-0011）。默认全部关闭；与 Codex sandbox 档位互不替代。
 */
enum class HostCapability(val level: Int) {
    /** L1：用户经 SAF 授权的工作区目录 */
    WORKSPACE_FS(1),

    /** L2：系统可见 Intent 协作（仅 Lab 通道） */
    SHARE_INTENT(2),

    /** L3：无障碍屏幕代理（仅 Lab 通道） */
    UI_AUTOMATION(3),

    /** L4：特权 shell（仅 Lab 通道） */
    PRIVILEGED_SHELL(4),
}

enum class HostToolName(val wireName: String, val capability: HostCapability, val isWrite: Boolean) {
    WORKSPACE_LIST("workspace.list", HostCapability.WORKSPACE_FS, isWrite = false),
    WORKSPACE_READ("workspace.read", HostCapability.WORKSPACE_FS, isWrite = false),
    WORKSPACE_WRITE("workspace.write", HostCapability.WORKSPACE_FS, isWrite = true),
    WORKSPACE_MKDIR("workspace.mkdir", HostCapability.WORKSPACE_FS, isWrite = true),
    WORKSPACE_REMOVE("workspace.remove", HostCapability.WORKSPACE_FS, isWrite = true),
    WORKSPACE_STAT("workspace.stat", HostCapability.WORKSPACE_FS, isWrite = false),
    ;

    companion object {
        fun fromWire(name: String): HostToolName? = entries.firstOrNull { it.wireName == name }
    }
}

data class HostAuthToken(
    val value: String,
    val conversationId: String,
    val instanceId: String,
    val expiresAtEpochMs: Long,
)

data class HostToolCall(
    val conversationId: String,
    val instanceId: String,
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val auth: HostAuthToken,
)

sealed class HostToolResult {
    data class Ok(
        val payload: Map<String, String>,
        val truncated: Boolean = false,
    ) : HostToolResult()

    data class Denied(
        val code: String,
        val userMessage: String,
    ) : HostToolResult()

    data class Error(
        val code: String,
        val userMessage: String,
    ) : HostToolResult()
}

data class WorkspaceGrant(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val createdAtEpochMs: Long,
)

data class WorkspaceEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
)

data class HostAuditEvent(
    val epochMs: Long,
    val tool: String,
    val capability: String,
    val outcome: String,
    val code: String?,
    val conversationIdHash: String,
    val durationMs: Long,
)

object HostLimits {
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_LIST_ENTRIES = 500
    const val MAX_RECURSION_DEPTH = 8
    const val READ_TIMEOUT_HINT_MS = 15_000L
    const val WRITE_TIMEOUT_HINT_MS = 30_000L
    const val TOKEN_TTL_MS = 15L * 60 * 1_000
    const val MAX_AUDIT_EVENTS = 200
}
