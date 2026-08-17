package com.agentdeck.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCliCatalogTest {
    @Test
    fun codex_dsh_and_pi_are_downloadable_claude_is_not() {
        val kinds = RuntimeCliCatalog.kinds(
            codexVersion = "0.147.0",
            codexDownloadBytes = 121_000_000L,
            dshDownloadBytes = 57_128_466L,
            dshVersionLabel = "DeepSeek Harness 0.1.0-rc.6 + Node v24.19.0",
            piDownloadBytes = 12_000_000L,
            piVersionLabel = "pi 0.84.2",
        )
        val codex = kinds.single { it.id == RuntimeCliCatalog.CODEX }
        assertTrue(codex.available)
        assertEquals(RuntimeCliSurface.NATIVE_CHAT, codex.surface)
        assertEquals(121_000_000L, codex.versions.single().downloadBytes)

        val dsh = kinds.single { it.id == RuntimeCliCatalog.DEEPSEEK_HARNESS }
        assertTrue(dsh.available)
        assertFalse(dsh.comingSoon)
        assertEquals(RuntimeCliSurface.WEB_UI, dsh.surface)
        assertEquals(57_128_466L, dsh.versions.single().downloadBytes)

        val pi = kinds.single { it.id == RuntimeCliCatalog.PI }
        assertTrue(pi.available)
        assertFalse(pi.comingSoon)
        assertEquals(RuntimeCliSurface.TERMINAL_AGENT, pi.surface)
        assertEquals(12_000_000L, pi.versions.single().downloadBytes)

        val claude = kinds.single { it.id == RuntimeCliCatalog.CLAUDE_CODE }
        assertFalse(claude.available)
        assertTrue(claude.comingSoon)
    }

    @Test
    fun surfaces_have_labels() {
        assertEquals("Web UI", RuntimeCliCatalog.surfaceLabel(RuntimeCliSurface.WEB_UI))
        assertEquals("终端 Agent", RuntimeCliCatalog.surfaceLabel(RuntimeCliSurface.TERMINAL_AGENT))
    }
}
