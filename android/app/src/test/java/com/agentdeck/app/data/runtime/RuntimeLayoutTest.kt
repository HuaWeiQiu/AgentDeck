package com.agentdeck.app.data.runtime

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
            File(root, "a").apply { parentFile.mkdirs(); writeText("12345") }
            File(root, "nested/b").apply { parentFile.mkdirs(); writeText("abcd") }
            assertEquals(9L, directorySize(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cli_layout_names_keep_user_data_out_of_codex_rootfs() {
        assertTrue("runtimes/codex".startsWith("runtimes/"))
        assertFalse("codex-home".startsWith("runtimes/"))
        assertFalse("projects".startsWith("runtimes/"))
    }
}
