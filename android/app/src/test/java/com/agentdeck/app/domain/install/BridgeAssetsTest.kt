package com.agentdeck.app.domain.install

import org.junit.Assert.assertEquals
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
    fun `bridge assets match packaged copies and pass syntax checks`() {
        val names = listOf("codex-app-server-start.sh", "codex-app-server-bridge.py")
        names.forEach { name ->
            val source = File(repoRoot, "wrappers/$name")
            val packaged = File(repoRoot, "android/app/src/main/assets/wrappers/$name")
            assertEquals(name, source.readText(), packaged.readText())
        }

        assertProcessSucceeds("bash", "-n", File(repoRoot, "wrappers/codex-app-server-start.sh").path)
        assertProcessSucceeds(
            "python3",
            "-c",
            "compile(open(r'${File(repoRoot, "wrappers/codex-app-server-bridge.py").path}', " +
                "encoding='utf-8').read(), 'codex-app-server-bridge.py', 'exec')",
        )
    }

    @Test
    fun `bridge is loopback only token protected and bounded`() {
        val bridge = File(repoRoot, "wrappers/codex-app-server-bridge.py").readText()
        val start = File(repoRoot, "wrappers/codex-app-server-start.sh").readText()

        assertTrue(bridge.contains("HOST = \"127.0.0.1\""))
        assertTrue(bridge.contains("secrets.token_urlsafe(32)"))
        assertTrue(bridge.contains("hmac.compare_digest"))
        assertTrue(bridge.contains("MAX_LINE_BYTES = 1024 * 1024"))
        assertTrue(bridge.contains("listener.listen(1)"))
        assertTrue(bridge.contains("\"check_for_update_on_startup=false\""))
        assertTrue(bridge.contains("\"app-server\""))
        assertTrue(bridge.contains("\"--listen\""))
        assertTrue(bridge.contains("\"stdio://\""))
        assertTrue(bridge.contains("write_lease"))
        assertTrue(bridge.contains("remove_owned_lease"))
        assertTrue(bridge.contains("signal.SIGTERM"))
        assertTrue(start.contains("/proc/${'$'}existing_pid/cmdline"))
        assertTrue(start.contains("bridge.${'$'}{INSTANCE_KEY}.pid"))
        assertTrue(start.contains("--instance-key \"${'$'}INSTANCE_KEY\""))
        assertTrue(start.contains("chmod 700 -- \"${'$'}RUNTIME_ROOT\""))
        assertTrue(start.contains("rm -f -- \"${'$'}BOOTSTRAP\""))
        assertTrue(!start.contains("set -x"))
    }

    private fun assertProcessSucceeds(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
