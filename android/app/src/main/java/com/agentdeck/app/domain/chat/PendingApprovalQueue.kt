package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId

internal class PendingApprovalQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val pending = ArrayDeque<ChatApproval>()

    init {
        require(capacity > 0)
    }

    fun offer(approval: ChatApproval): Boolean {
        if (pending.size >= capacity) return false
        pending.addLast(approval)
        return true
    }

    fun poll(): ChatApproval? = pending.removeFirstOrNull()

    fun remove(requestId: RpcRequestId) {
        pending.removeAll { it.requestId == requestId }
    }

    fun clear() = pending.clear()

    fun snapshot(): List<ChatApproval> = pending.toList()

    fun restore(approvals: List<ChatApproval>) {
        pending.clear()
        approvals.take(capacity).forEach(pending::addLast)
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
