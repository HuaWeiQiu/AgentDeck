package com.agentdeck.app.ui.chat

/**
 * Buffers `item/agentMessage/delta` payloads so the ViewModel can flush them into
 * state on a throttle instead of copying the whole timeline per token.
 *
 * The coalescer itself is synchronous and time-injectable for unit tests; the
 * ViewModel owns the coroutine that periodically calls [drain].
 */
internal class StreamingDeltaCoalescer(
    private val flushIntervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val buffer = StringBuilder()
    private var lastFlushAt: Long? = null

    val isEmpty: Boolean get() = buffer.isEmpty()

    fun append(delta: String) {
        buffer.append(delta)
    }

    /** True when buffered deltas have waited at least [flushIntervalMs]. */
    fun isFlushDue(): Boolean {
        if (buffer.isEmpty()) return false
        val flushedAt = lastFlushAt ?: return true
        return now() - flushedAt >= flushIntervalMs
    }

    /** Drain buffered deltas, or null when nothing is pending. */
    fun drain(): String? {
        if (buffer.isEmpty()) return null
        val text = buffer.toString()
        buffer.setLength(0)
        lastFlushAt = now()
        return text
    }
}
