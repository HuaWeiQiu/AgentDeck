package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.DenyAllHostApprovalGateway
import com.agentdeck.app.domain.host.HostApprovalGateway
import com.agentdeck.app.domain.host.HostToolCall
import com.agentdeck.app.domain.host.HostToolName

/**
 * 运行时替换审批实现；未绑定时 fail closed。
 */
class MutableHostApprovalGateway : HostApprovalGateway {
    @Volatile
    var delegate: HostApprovalGateway = DenyAllHostApprovalGateway

    override suspend fun requestWriteApproval(
        call: HostToolCall,
        tool: HostToolName,
        summary: String,
    ): Boolean = delegate.requestWriteApproval(call, tool, summary)
}
