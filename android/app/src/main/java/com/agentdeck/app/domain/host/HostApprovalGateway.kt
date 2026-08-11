package com.agentdeck.app.domain.host

/**
 * Host 写操作审批。与 Codex sandbox 审批独立。
 * 默认实现应 fail closed（拒绝）。
 */
fun interface HostApprovalGateway {
    suspend fun requestWriteApproval(
        call: HostToolCall,
        tool: HostToolName,
        summary: String,
    ): Boolean
}

object DenyAllHostApprovalGateway : HostApprovalGateway {
    override suspend fun requestWriteApproval(
        call: HostToolCall,
        tool: HostToolName,
        summary: String,
    ): Boolean = false
}
