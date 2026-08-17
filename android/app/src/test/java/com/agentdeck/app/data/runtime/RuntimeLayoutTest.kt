package com.agentdeck.app.data.runtime

import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeLayoutContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLayoutTest {
    @Test
    fun directory_size_counts_nested_files_only() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentdeck-size-" + System.nanoTime())
        try {
            File(root, "a").apply { parentFile!!.mkdirs(); writeText("12345") }
            File(root, "nested/b").apply { parentFile!!.mkdirs(); writeText("abcd") }
            assertEquals(9L, directorySize(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remove_codex_targets_only_cli_tree_paths() {
        // Documented contract for EmbeddedRuntimePaths.removeCodexRuntime():
        // delete rootfs/staging/downloads under runtimes/codex (+ legacy root locations),
        // never shared user-data directories at the runtime root.
        val deleted = setOf(
            RuntimeLayoutContract.rootfsRelative(RuntimeCliCatalog.CODEX, "release"),
            RuntimeLayoutContract.stagingRootfsRelative(RuntimeCliCatalog.CODEX, "release"),
            RuntimeLayoutContract.downloadsRelative(RuntimeCliCatalog.CODEX),
            "rootfs-release", // legacy pre-migration location
            ".rootfs-release.staging",
        )
        deleted.forEach { path ->
            assertFalse(
                "delete target must not be shared user data: $path",
                RuntimeLayoutContract.isSharedUserData(path),
            )
        }
        RuntimeLayoutContract.sharedUserDataRelatives().forEach { shared ->
            assertTrue(RuntimeLayoutContract.isSharedUserData(shared))
            assertFalse(RuntimeLayoutContract.isUnderCliTree(shared, RuntimeCliCatalog.CODEX))
        }
    }

    @Test
    fun cli_layout_names_keep_user_data_out_of_codex_rootfs() {
        assertTrue(RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.CODEX).startsWith("runtimes/"))
        assertFalse(RuntimeLayoutContract.CODEX_HOME.startsWith("runtimes/"))
        assertFalse(RuntimeLayoutContract.PROJECTS.startsWith("runtimes/"))
    }
}
