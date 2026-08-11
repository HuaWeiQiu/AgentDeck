package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId

internal class PendingUserInputQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val pending = ArrayDeque<ChatUserInputRequest>()

    init {
        require(capacity > 0)
    }

    fun offer(request: ChatUserInputRequest): Boolean {
        if (pending.size >= capacity) return false
        pending.addLast(request)
        return true
    }

    fun poll(): ChatUserInputRequest? = pending.removeFirstOrNull()

    fun remove(requestId: RpcRequestId) {
        pending.removeAll { it.requestId == requestId }
    }

    fun clear() = pending.clear()

    fun snapshot(): List<ChatUserInputRequest> = pending.toList()

    fun restore(requests: List<ChatUserInputRequest>) {
        pending.clear()
        requests.take(capacity).forEach(pending::addLast)
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
