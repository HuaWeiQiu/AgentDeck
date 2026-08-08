package com.agentdeck.app.domain.env

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DoctorOutputParserTest {
    @Test
    fun `parser ignores unrelated process output`() {
        val markers = DoctorOutputParser.parse(
            """
            proot banner
            codex_installed	ready	codex-cli 1.2.3
            malformed	line
            codex_authenticated	action_required	需要登录
            """.trimIndent(),
        )

        assertEquals(2, markers.size)
        assertEquals(EnvironmentCheckStatus.READY, markers["codex_installed"]?.status)
        assertEquals(
            EnvironmentCheckStatus.ACTION_REQUIRED,
            markers["codex_authenticated"]?.status,
        )
    }

    @Test
    fun `doctor emits complete blocked state without proot`() {
        val home = Files.createTempDirectory("agentdeck-doctor-blocked").toFile()
        try {
            val result = runDoctor(home, pathPrefix = home)
            val markers = DoctorOutputParser.parse(result.output)

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(6, markers.size)
            assertEquals(
                EnvironmentCheckStatus.ACTION_REQUIRED,
                markers["proot_distro"]?.status,
            )
            assertEquals(
                EnvironmentCheckStatus.BLOCKED,
                markers["codex_authenticated"]?.status,
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor emits all ready markers for prepared environment`() {
        val home = Files.createTempDirectory("agentdeck-doctor-ready").toFile()
        try {
            File(home, ".termux").mkdirs()
            File(home, ".termux/termux.properties").writeText("allow-external-apps=true\n")
            val wrapper = File(home, ".agentdeck/wrappers/codex-ubuntu.sh")
            wrapper.parentFile?.mkdirs()
            wrapper.writeText("#!/bin/bash\n")
            assertTrue(wrapper.setExecutable(true))

            val binDir = File(home, "bin").apply { mkdirs() }
            val proot = File(binDir, "proot-distro")
            proot.writeText(
                """
                #!/bin/bash
                printf 'codex_installed\tready\tcodex-cli 1.2.3\n'
                printf 'codex_authenticated\tready\t已登录\n'
                """.trimIndent(),
            )
            assertTrue(proot.setExecutable(true))

            val result = runDoctor(home, pathPrefix = binDir)
            val markers = DoctorOutputParser.parse(result.output)

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(6, markers.size)
            assertTrue(markers.values.all { it.status == EnvironmentCheckStatus.READY })
        } finally {
            home.deleteRecursively()
        }
    }

    private fun runDoctor(home: File, pathPrefix: File): ProcessResult {
        val process = ProcessBuilder(
            "/bin/bash",
            "-c",
            EnvironmentProbe.DOCTOR_SCRIPT,
        ).redirectErrorStream(true)
        process.environment()["HOME"] = home.absolutePath
        process.environment()["PATH"] =
            pathPrefix.absolutePath + File.pathSeparator + "/usr/bin:/bin"
        val running = process.start()
        val output = running.inputStream.bufferedReader().readText()
        return ProcessResult(running.waitFor(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
