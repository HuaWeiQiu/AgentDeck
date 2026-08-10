package com.agentdeck.app.domain.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        val helper = File(repoRoot, "wrappers/codex-provider-token.py")
        val packagedHelper = File(
            repoRoot,
            "android/app/src/main/assets/wrappers/codex-provider-token.py",
        )
        assertEquals(helper.readText(), packagedHelper.readText())
        assertProcessSucceeds("python3", "-m", "py_compile", helper.path)
    }

    @Test
    fun `app server uses authenticated loopback websocket and owned lifecycle`() {
        val start = File(repoRoot, "wrappers/codex-app-server-start.sh").readText()

        assertTrue(start.contains("START_CONTRACT_VERSION=7"))
        assertFalse(start.contains("--profile agentdeck"))
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
        assertTrue(start.contains("model_providers.${'$'}{provider_id}.auth.command"))
        assertTrue(start.contains("credential_token"))
        assertTrue(start.contains("unlink -- \"${'$'}START_LOG\""))
        assertFalse(start.contains("api_key"))
        assertFalse(start.contains("pkill"))
        assertFalse(start.contains("app-server --listen stdio://"))
        assertFalse(start.contains("nohup python3"))
        assertFalse(start.contains("set -x"))
    }

    @Test
    fun `provider helper exchanges capability token for stdout credential`() {
        val helper = File(repoRoot, "wrappers/codex-provider-token.py")
        val token = "a".repeat(64)
        val tokenFile = Files.createTempFile("agentdeck-helper", ".token")
        Files.write(tokenFile, "$token\n".toByteArray())
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = thread(name = "provider-helper-test") {
            try {
                server.accept().use { socket ->
                    val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                    assertEquals(token, request.getString("token"))
                    assertEquals("cred_test", request.getString("credential_ref"))
                    val response = JSONObject()
                        .put("ok", true)
                        .put(
                            "api_key_b64",
                            Base64.getEncoder().encodeToString("sk-test-secret".toByteArray()),
                        )
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write(response.toString())
                        output.newLine()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }
        try {
            val process = ProcessBuilder(
                "python3",
                helper.path,
                "--port",
                server.localPort.toString(),
                "--token-file",
                tokenFile.toString(),
                "--credential-ref",
                "cred_test",
            ).start()
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)

            assertEquals(stderr, 0, process.waitFor())
            assertEquals("sk-test-secret", stdout)
            serverThread.join(2_000)
            assertFalse(serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("helper test server failed", it) }
        } finally {
            server.close()
            Files.deleteIfExists(tokenFile)
        }
    }

    private fun assertProcessSucceeds(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
