package com.agentdeck.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCliCatalogTest {
    @Test
    fun default_catalog_only_makes_codex_downloadable() {
        val kinds = RuntimeCliCatalog.kinds(codexVersion = "0.147.0", downloadBytes = 121_000_000L)
        val codex = kinds.single { it.id == RuntimeCliCatalog.CODEX }
        assertTrue(codex.available)
        assertEquals("Codex 0.147.0", codex.versions.single().label)
        assertEquals(121_000_000L, codex.versions.single().downloadBytes)
        kinds.filterNot { it.id == RuntimeCliCatalog.CODEX }.forEach { kind ->
            assertFalse(kind.available)
            assertTrue(kind.comingSoon)
            assertTrue(kind.versions.isEmpty())
        }
    }
}
