package com.agentdeck.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLayoutContractTest {
    @Test
    fun codex_rootfs_lives_under_per_cli_tree() {
        assertEquals("runtimes/codex", RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.CODEX))
        assertEquals(
            "runtimes/codex/rootfs-arm64-ubuntu-24.04-codex-0.147.0",
            RuntimeLayoutContract.rootfsRelative(
                RuntimeCliCatalog.CODEX,
                "arm64-ubuntu-24.04-codex-0.147.0",
            ),
        )
        assertEquals(
            "runtimes/codex/downloads",
            RuntimeLayoutContract.downloadsRelative(RuntimeCliCatalog.CODEX),
        )
    }

    @Test
    fun future_cli_trees_do_not_overlap_codex() {
        val deepseek = RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.DEEPSEEK_HARNESS)
        val pi = RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.PI)
        val claude = RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.CLAUDE_CODE)
        val codex = RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.CODEX)
        assertEquals("runtimes/deepseek-harness", deepseek)
        assertEquals("runtimes/pi", pi)
        assertEquals("runtimes/claude-code", claude)
        assertFalse(RuntimeLayoutContract.isUnderCliTree(deepseek, RuntimeCliCatalog.CODEX))
        assertFalse(RuntimeLayoutContract.isUnderCliTree(codex, RuntimeCliCatalog.DEEPSEEK_HARNESS))
        assertTrue(RuntimeLayoutContract.isUnderCliTree("$deepseek/rootfs-x", RuntimeCliCatalog.DEEPSEEK_HARNESS))
    }

    @Test
    fun shared_user_data_is_outside_any_cli_tree() {
        RuntimeLayoutContract.sharedUserDataRelatives().forEach { relative ->
            assertFalse(
                "shared path must not sit under codex rootfs tree: $relative",
                RuntimeLayoutContract.isUnderCliTree(relative, RuntimeCliCatalog.CODEX),
            )
            assertTrue(RuntimeLayoutContract.isSharedUserData(relative))
            assertTrue(RuntimeLayoutContract.isSharedUserData("$relative/child"))
        }
        assertFalse(RuntimeLayoutContract.isSharedUserData("runtimes/codex/rootfs-x"))
        assertFalse(RuntimeLayoutContract.isSharedUserData("runtimes/codex/downloads/a.tgz"))
    }

    @Test
    fun catalog_placeholders_stay_non_downloadable() {
        val kinds = RuntimeCliCatalog.kinds(
            codexVersion = "0.147.0",
            codexDownloadBytes = 1L,
            dshDownloadBytes = 1L,
            dshVersionLabel = "test",
        )
        // Directory names are reserved for every CLI id.
        kinds.forEach { kind ->
            assertTrue(RuntimeLayoutContract.cliRootRelative(kind.id).startsWith("runtimes/"))
        }
        // Only Claude Code remains a non-downloadable placeholder; pi is on-demand.
        val claude = kinds.single { it.id == RuntimeCliCatalog.CLAUDE_CODE }
        assertFalse(claude.available)
        assertTrue(claude.comingSoon)
        assertTrue(kinds.single { it.id == RuntimeCliCatalog.PI }.available)
    }
}
