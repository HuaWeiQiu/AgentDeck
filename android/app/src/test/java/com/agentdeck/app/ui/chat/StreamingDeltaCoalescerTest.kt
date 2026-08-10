package com.agentdeck.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDeltaCoalescerTest {
    @Test
    fun `deltas accumulate until drained`() {
        val coalescer = StreamingDeltaCoalescer(flushIntervalMs = 64)

        coalescer.append("hel")
        coalescer.append("lo")

        assertEquals("hello", coalescer.drain())
        assertTrue(coalescer.isEmpty)
        assertNull(coalescer.drain())
    }

    @Test
    fun `flush is due only after the interval elapses`() {
        var now = 1_000L
        val coalescer = StreamingDeltaCoalescer(flushIntervalMs = 64, now = { now })

        coalescer.append("a")
        assertTrue("first flush must be due immediately", coalescer.isFlushDue())

        assertEquals("a", coalescer.drain())
        coalescer.append("b")
        assertFalse(coalescer.isFlushDue())

        now += 64
        assertTrue(coalescer.isFlushDue())

        now += 1
        assertTrue("empty buffer is never due", StreamingDeltaCoalescer(64, now = { now }).isFlushDue().not())
    }
}
