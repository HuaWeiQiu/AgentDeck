package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NodeStartupSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shellExports creates cache dir and sets NODE_COMPILE_CACHE`() {
        val snippet = NodeStartupSupport.shellExports("/opt/agentdeck-pi/.node-compile-cache")
        assertTrue(snippet.contains("NODE_COMPILE_CACHE="))
        assertTrue(snippet.contains("/opt/agentdeck-pi/.node-compile-cache"))
        assertTrue(snippet.contains("mkdir -p"))
    }

    @Test
    fun `writeTextIfChanged skips identical content`() {
        val file = File(tmp.root, "models.json")
        assertTrue(NodeStartupSupport.writeTextIfChanged(file, "hello\n"))
        assertEquals("hello\n", file.readText())
        assertFalse(NodeStartupSupport.writeTextIfChanged(file, "hello\n"))
        assertTrue(NodeStartupSupport.writeTextIfChanged(file, "world\n"))
        assertEquals("world\n", file.readText())
    }

    @Test
    fun `ensureHostCacheDir creates directory under cli root`() {
        val cli = tmp.newFolder("pi")
        val cache = NodeStartupSupport.ensureHostCacheDir(cli)
        assertTrue(cache.isDirectory)
        assertEquals(NodeStartupSupport.CACHE_DIR_NAME, cache.name)
    }
}
