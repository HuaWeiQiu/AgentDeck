package com.agentdeck.app.domain.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WrapperContractTest {
    private val repoRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir).canonicalFile) {
            it.parentFile
        }
            .first { File(it, "README.md").isFile && File(it, "android/app").isDirectory }
    }

    private val assetWrapper: File
        get() = File(repoRoot, "android/app/src/main/assets/wrappers/codex-ubuntu.sh")

    @Test
    fun `repository and packaged wrappers stay identical`() {
        val repositoryWrapper = File(repoRoot, "wrappers/codex-ubuntu.sh")

        assertEquals(repositoryWrapper.readText(), assetWrapper.readText())
    }

    @Test
    fun `wrapper forwards dynamic values as argv instead of shell source`() {
        val tempDir = Files.createTempDirectory("agentdeck-wrapper-test").toFile()
        try {
            val capture = File(tempDir, "args.bin")
            val fakeProot = File(tempDir, "proot-distro")
            fakeProot.writeText(
                """
                #!/bin/bash
                printf '%s\0' "${'$'}@" > "${'$'}{AGENTDECK_CAPTURE:?}"
                """.trimIndent(),
            )
            assertTrue(fakeProot.setExecutable(true))

            val workspace = "/root/project with spaces/'quote'; \$(touch nope)"
            val cliArg = "'; echo nope"
            val process = ProcessBuilder(
                "/bin/bash",
                assetWrapper.absolutePath,
                "--distro",
                "ubuntu",
                "--cwd",
                workspace,
                "--bin",
                "codex",
                "--approval-policy",
                "never",
                "--",
                "resume",
                cliArg,
            ).redirectErrorStream(true)
            process.environment()["PATH"] =
                tempDir.absolutePath + File.pathSeparator + process.environment()["PATH"]
            process.environment()["AGENTDECK_CAPTURE"] = capture.absolutePath

            val running = process.start()
            val output = running.inputStream.bufferedReader().readText()
            assertEquals(output, 0, running.waitFor())

            val captured = capture.readText().split('\u0000').filter { it.isNotEmpty() }
            assertEquals(
                listOf("login", "ubuntu", "--", "/usr/bin/env", "bash", "-c"),
                captured.take(6),
            )
            val fixedInnerScript = captured[6]
            assertFalse(fixedInnerScript.contains(workspace))
            assertFalse(fixedInnerScript.contains(cliArg))
            assertTrue(fixedInnerScript.contains("check_for_update_on_startup=false"))
            assertTrue(fixedInnerScript.contains("--sandbox danger-full-access"))
            assertTrue(fixedInnerScript.contains("--ask-for-approval \"${'$'}approval_policy\""))
            assertEquals("agentdeck", captured[7])
            assertEquals(workspace, captured[8])
            assertEquals("codex", captured[9])
            assertEquals("never", captured[10])
            assertEquals(listOf("resume", cliArg), captured.drop(11))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `wrapper rejects unknown approval policy`() {
        val process = ProcessBuilder(
            "/bin/bash",
            assetWrapper.absolutePath,
            "--approval-policy",
            "on-request;touch-pwned",
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(64, process.waitFor())
        assertTrue(output.contains("invalid approval policy"))
    }

    @Test
    fun `wrapper rejects unknown options before invoking proot`() {
        val process = ProcessBuilder(
            "/bin/bash",
            assetWrapper.absolutePath,
            "--unsafe-option",
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(64, process.waitFor())
        assertTrue(output.contains("unknown option"))
    }

    @Test
    fun `wrapper rejects executable shell syntax`() {
        val process = ProcessBuilder(
            "/bin/bash",
            assetWrapper.absolutePath,
            "--bin",
            "codex;touch-pwned",
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(64, process.waitFor())
        assertTrue(output.contains("invalid CLI executable"))
    }
}
