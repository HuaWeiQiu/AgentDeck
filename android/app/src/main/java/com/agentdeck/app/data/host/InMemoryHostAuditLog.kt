package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.HostAuditEvent
import com.agentdeck.app.domain.host.HostLimits
import java.security.MessageDigest
import java.util.ArrayDeque

class InMemoryHostAuditLog(
    private val capacity: Int = HostLimits.MAX_AUDIT_EVENTS,
) {
    private val events = ArrayDeque<HostAuditEvent>(capacity)

    @Synchronized
    fun append(event: HostAuditEvent) {
        if (events.size >= capacity) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<HostAuditEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    companion object {
        fun hashConversationId(conversationId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(conversationId.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
