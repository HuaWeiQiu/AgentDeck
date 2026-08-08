package com.agentdeck.app.domain.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeAssetsTest {
    private val repoRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir).canonicalFile) { it.parentFile }
            .first { File(it, "README.md").isFile && File(it, "android/app").isDirectory }
    }

    @Test
    fun `app server launcher matches packaged copy and passes syntax check`() {
        val source = File(repoRoot, "wrappers/codex-app-server-start.sh")
        val packaged = File(repoRoot, "android/app/src/main/assets/wrappers/codex-app-server-start.sh")

        assertEquals(source.readText(), packaged.readText())
        assertProcessSucceeds("bash", "-n", source.path)
    }

    @Test
    fun `app server uses authenticated loopback websocket and owned lifecycle`() {
        val start = File(repoRoot, "wrappers/codex-app-server-start.sh").readText()

        assertTrue(start.contains("START_CONTRACT_VERSION=6"))
        assertTrue(start.contains("--listen ws://127.0.0.1:0"))
        assertTrue(start.contains("--ws-auth capability-token"))
        assertTrue(start.contains("--ws-token-file"))
        assertTrue(start.contains("od -An -N32 -tx1 /dev/urandom"))
        assertTrue(start.contains("chmod 600 -- \"${'$'}TOKEN_FILE\""))
        assertTrue(start.contains("agentdeck-app-server-${'$'}INSTANCE_KEY"))
        assertTrue(start.contains("read_owned_pid"))
        assertTrue(start.contains("stop_marked_servers"))
        assertTrue(start.contains("terminate_tree"))
        assertTrue(start.contains("tree+=(\"${'$'}child_pid\")"))
        assertTrue(start.contains("previous AgentDeck app-server did not stop"))
        assertFalse(start.contains("done < \"${'$'}cmdline\" 2>/dev/null || continue"))
        assertTrue(start.contains("SUPERVISOR_COMMAND"))
        assertTrue(start.contains("--stop"))
        assertTrue(start.contains("stop_legacy_bridge"))
        assertTrue(start.contains("check_for_update_on_startup=false"))
        assertFalse(start.contains("pkill"))
        assertFalse(start.contains("app-server --listen stdio://"))
        assertFalse(start.contains("nohup python3"))
        assertFalse(start.contains("set -x"))
    }

    private fun assertProcessSucceeds(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
