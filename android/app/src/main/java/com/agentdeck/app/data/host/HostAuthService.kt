package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.HostAuthToken
import com.agentdeck.app.domain.host.HostLimits
import java.security.SecureRandom

/**
 * 内存短时 token。不落盘、不进 rootfs、不进 Room。
 */
class HostAuthService(
    private val ttlMs: Long = HostLimits.TOKEN_TTL_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Issued(
        val conversationId: String,
        val instanceId: String,
        val expiresAtEpochMs: Long,
    )

    private val issued = LinkedHashMap<String, Issued>()

    @Synchronized
    fun mint(conversationId: String, instanceId: String, nowEpochMs: Long): HostAuthToken {
        require(conversationId.isNotBlank() && instanceId.isNotBlank()) { "会话标识无效" }
        purgeExpired(nowEpochMs)
        val value = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val expires = nowEpochMs + ttlMs
        issued[value] = Issued(conversationId, instanceId, expires)
        // bound map size
        while (issued.size > 64) {
            val oldest = issued.entries.first()
            issued.remove(oldest.key)
        }
        return HostAuthToken(
            value = value,
            conversationId = conversationId,
            instanceId = instanceId,
            expiresAtEpochMs = expires,
        )
    }

    @Synchronized
    fun validate(token: HostAuthToken, conversationId: String, instanceId: String, nowEpochMs: Long): String? {
        purgeExpired(nowEpochMs)
        val record = issued[token.value]
            ?: return "host_auth_unknown"
        if (record.expiresAtEpochMs < nowEpochMs) {
            issued.remove(token.value)
            return "host_auth_expired"
        }
        if (record.conversationId != conversationId || token.conversationId != conversationId) {
            return "host_auth_conversation_mismatch"
        }
        if (record.instanceId != instanceId || token.instanceId != instanceId) {
            return "host_auth_instance_mismatch"
        }
        return null
    }

    @Synchronized
    fun revokeAll() {
        issued.clear()
    }

    private fun purgeExpired(nowEpochMs: Long) {
        val iterator = issued.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtEpochMs < nowEpochMs) iterator.remove()
        }
    }
}
