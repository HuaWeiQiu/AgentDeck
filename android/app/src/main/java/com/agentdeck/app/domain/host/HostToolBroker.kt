package com.agentdeck.app.domain.host

interface HostToolBroker {
    fun mintToken(conversationId: String, instanceId: String, nowEpochMs: Long = System.currentTimeMillis()): HostAuthToken
    fun listEnabledCapabilities(): Set<HostCapability>
    suspend fun invoke(call: HostToolCall, nowEpochMs: Long = System.currentTimeMillis()): HostToolResult
    fun recentAudit(): List<HostAuditEvent>
}
