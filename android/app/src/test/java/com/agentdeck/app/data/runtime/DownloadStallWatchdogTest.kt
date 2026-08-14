package com.agentdeck.app.data.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStallWatchdogTest {
    @Test
    fun `healthy throughput resets the window without throwing`() {
        var now = 0L
        val watchdog = DownloadStallWatchdog(windowMs = 1_000, minBytesPerWindow = 100, nowMs = { now })
        watchdog.onBytes(50) // inside first window: no check
        now = 1_000
        watchdog.onBytes(50 + 100) // window 1: gained 100 >= 100 -> ok, reset
        now = 2_000
        watchdog.onBytes(150 + 250) // window 2: gained 250 -> ok
    }

    @Test
    fun `stalled mirror throws IOException so fallback can switch source`() {
        var now = 0L
        val watchdog = DownloadStallWatchdog(windowMs = 1_000, minBytesPerWindow = 64 * 1024, nowMs = { now })
        watchdog.onBytes(1_024)
        now = 15_000
        val error = assertThrows(IOException::class.java) {
            watchdog.onBytes(2_048) // 15s window gained only 2 KiB
        }
        assertTrue(error.message.orEmpty().contains("切换下一个源"))
    }

    @Test
    fun `resume offset does not count as fresh throughput`() {
        var now = 0L
        val watchdog = DownloadStallWatchdog(startBytes = 28L * 1024 * 1024, windowMs = 1_000, minBytesPerWindow = 100, nowMs = { now })
        now = 1_000
        // Total barely moved from the resumed offset: still a stall.
        assertThrows(IOException::class.java) {
            watchdog.onBytes(28L * 1024 * 1024 + 10)
        }
    }

    @Test
    fun `first window starts at construction time`() {
        var now = 41_000L
        val watchdog = DownloadStallWatchdog(windowMs = 1_000, minBytesPerWindow = 100, nowMs = { now })
        now = 41_500 // half a window: never checked
        watchdog.onBytes(1)
        now = 42_500 // 1.5 windows: checked against construction-time bytes
        assertThrows(IOException::class.java) { watchdog.onBytes(2) }
        assertEquals(42_500L, now)
    }
}
