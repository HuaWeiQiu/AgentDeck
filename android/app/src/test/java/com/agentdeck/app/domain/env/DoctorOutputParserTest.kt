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
            prepareTermuxFiles(home)

            val binDir = prepareDoctorRuntime(home, loginReady = true)

            val result = runDoctor(home, pathPrefix = binDir)
            val markers = DoctorOutputParser.parse(result.output)

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(6, markers.size)
            assertTrue(markers.values.all { it.status == EnvironmentCheckStatus.READY })
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor requests repair when legacy install lacks native chat launcher`() {
        val home = Files.createTempDirectory("agentdeck-doctor-legacy-wrapper").toFile()
        try {
            File(home, ".termux").mkdirs()
            File(home, ".termux/termux.properties").writeText("allow-external-apps=true\n")
            val legacyWrapper = File(home, ".agentdeck/wrappers/codex-ubuntu.sh")
            legacyWrapper.parentFile?.mkdirs()
            legacyWrapper.writeText("#!/bin/bash\n")
            assertTrue(legacyWrapper.setExecutable(true))

            val binDir = prepareDoctorRuntime(home, loginReady = true)

            val result = runDoctor(home, pathPrefix = binDir)
            val wrapper = DoctorOutputParser.parse(result.output)["codex_wrapper"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, wrapper?.status)
            assertEquals("需要补齐 1 个启动组件", wrapper?.detail)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor requests repair when native chat wrappers are outdated`() {
        val home = Files.createTempDirectory("agentdeck-doctor-old-wrapper").toFile()
        try {
            prepareTermuxFiles(home)
            File(home, ".agentdeck/wrappers/codex-ubuntu.sh").writeText("#!/bin/bash\n")
            val binDir = prepareDoctorRuntime(home, loginReady = true)

            val result = runDoctor(home, pathPrefix = binDir)
            val wrapper = DoctorOutputParser.parse(result.output)["codex_wrapper"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, wrapper?.status)
            assertEquals("需要更新 Native Chat 启动组件", wrapper?.detail)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor accepts Codex login status`() {
        val home = Files.createTempDirectory("agentdeck-doctor-auth-ready").toFile()
        try {
            prepareTermuxFiles(home)
            val binDir = prepareDoctorRuntime(home, loginReady = true)

            val result = runDoctor(home, pathPrefix = binDir)
            val auth = DoctorOutputParser.parse(result.output)["codex_authenticated"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(result.output, EnvironmentCheckStatus.READY, auth?.status)
            assertEquals("Codex 已确认认证状态", auth?.detail)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor distinguishes config file from usable credentials`() {
        val home = Files.createTempDirectory("agentdeck-doctor-config-only").toFile()
        try {
            prepareTermuxFiles(home)
            val codexHome = File(home, ".codex").apply { mkdirs() }
            File(codexHome, "config.toml").writeText("model = \"gpt-5\"\n")
            val binDir = prepareDoctorRuntime(home, loginReady = false)

            val result = runDoctor(home, pathPrefix = binDir)
            val auth = DoctorOutputParser.parse(result.output)["codex_authenticated"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, auth?.status)
            assertEquals("已发现 Codex 配置，但没有可用凭据", auth?.detail)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor detects active provider credential environment`() {
        val home = Files.createTempDirectory("agentdeck-doctor-provider-env").toFile()
        try {
            prepareTermuxFiles(home)
            val codexHome = File(home, ".codex").apply { mkdirs() }
            File(codexHome, "config.toml").writeText(
                """
                model_provider = "custom"
                [model_providers.custom]
                env_key = "CUSTOM_CODEX_TOKEN"
                """.trimIndent(),
            )
            val binDir = prepareDoctorRuntime(home, loginReady = false)

            val result = runDoctor(
                home,
                pathPrefix = binDir,
                environment = mapOf("CUSTOM_CODEX_TOKEN" to "configured"),
            )
            val auth = DoctorOutputParser.parse(result.output)["codex_authenticated"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(result.output, EnvironmentCheckStatus.READY, auth?.status)
            assertEquals("已检测到可用 Provider 配置", auth?.detail)
            assertTrue("configured" !in result.output)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor accepts active provider that does not declare auth env`() {
        val home = Files.createTempDirectory("agentdeck-doctor-provider-no-auth").toFile()
        try {
            prepareTermuxFiles(home)
            val codexHome = File(home, ".codex").apply { mkdirs() }
            File(codexHome, "config.toml").writeText(
                """
                model_provider = "custom"
                [model_providers.custom]
                base_url = "http://127.0.0.1:11434/v1"
                """.trimIndent(),
            )
            val loginMarker = File(home, "login-status-called")
            val binDir = prepareDoctorRuntime(
                home,
                loginReady = false,
                pythonProbeResult = "ready",
                loginHang = true,
                loginMarker = loginMarker,
            )

            val result = runDoctor(home, pathPrefix = binDir)
            val auth = DoctorOutputParser.parse(result.output)["codex_authenticated"]

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(EnvironmentCheckStatus.READY, auth?.status)
            assertEquals("已检测到可用 Provider 配置", auth?.detail)
            assertTrue("Provider 已就绪时不应调用 codex login status", !loginMarker.exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor bounds a hanging official login status probe`() {
        val home = Files.createTempDirectory("agentdeck-doctor-auth-timeout").toFile()
        try {
            prepareTermuxFiles(home)
            val binDir = prepareDoctorRuntime(
                home = home,
                loginReady = false,
                loginHang = true,
            )

            val startedAt = System.nanoTime()
            val result = runDoctor(home, pathPrefix = binDir)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            val auth = DoctorOutputParser.parse(result.output)["codex_authenticated"]

            assertEquals(result.output, 0, result.exitCode)
            assertTrue("login status 应被限时，实际 ${elapsedMillis}ms", elapsedMillis < 3_000)
            assertEquals(EnvironmentCheckStatus.ERROR, auth?.status)
            assertEquals("官方认证检查超时，未阻塞其它环境检测", auth?.detail)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor rejects a distro that is not Ubuntu 24 04`() {
        val home = Files.createTempDirectory("agentdeck-doctor-wrong-ubuntu").toFile()
        try {
            prepareTermuxFiles(home)
            val binDir = prepareDoctorRuntime(
                home = home,
                loginReady = true,
                ubuntuVersion = "22.04",
            )

            val result = runDoctor(home, pathPrefix = binDir)
            val markers = DoctorOutputParser.parse(result.output)

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(
                EnvironmentCheckStatus.ACTION_REQUIRED,
                markers["ubuntu_installed"]?.status,
            )
            assertEquals(
                "需要 Ubuntu 24.04，当前为 ubuntu 22.04",
                markers["ubuntu_installed"]?.detail,
            )
            assertEquals(EnvironmentCheckStatus.BLOCKED, markers["codex_installed"]?.status)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `doctor requests update for Codex below supported version`() {
        val home = Files.createTempDirectory("agentdeck-doctor-old-codex").toFile()
        try {
            prepareTermuxFiles(home)
            val binDir = prepareDoctorRuntime(
                home = home,
                loginReady = true,
                codexVersion = "0.146.0",
            )

            val result = runDoctor(home, pathPrefix = binDir)
            val markers = DoctorOutputParser.parse(result.output)

            assertEquals(result.output, 0, result.exitCode)
            assertEquals(
                EnvironmentCheckStatus.ACTION_REQUIRED,
                markers["codex_installed"]?.status,
            )
            assertEquals(
                "需要 Codex 0.147.0 或更高版本，当前为 codex-cli 0.146.0",
                markers["codex_installed"]?.detail,
            )
            assertEquals(EnvironmentCheckStatus.BLOCKED, markers["codex_authenticated"]?.status)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun prepareTermuxFiles(home: File) {
        File(home, ".termux").mkdirs()
        File(home, ".termux/termux.properties").writeText("allow-external-apps=true\n")
        listOf(
            "codex-ubuntu.sh",
            "codex-app-server-start.sh",
        ).forEach { name ->
            val wrapper = File(home, ".agentdeck/wrappers/$name")
            wrapper.parentFile?.mkdirs()
            wrapper.writeText(
                "#!/bin/bash\n" +
                    "# check_for_update_on_startup=false\n" +
                    "# --sandbox danger-full-access\n" +
                    "# --ask-for-approval on-request\n" +
                    "# --instance-key\n" +
                    "# START_CONTRACT_VERSION=6\n" +
                    "# --listen ws://127.0.0.1:0\n" +
                    "# --ws-auth capability-token\n" +
                    "# --ws-token-file\n",
            )
            assertTrue(wrapper.setExecutable(true))
        }
    }

    private fun prepareDoctorRuntime(
        home: File,
        loginReady: Boolean,
        pythonProbeResult: String = "configured",
        ubuntuVersion: String = "24.04",
        codexVersion: String = "0.147.0",
        loginHang: Boolean = false,
        loginMarker: File? = null,
    ): File {
        val binDir = File(home, "bin").apply { mkdirs() }
        val fakeUbuntu = File(home, "fake-ubuntu")
        File(fakeUbuntu, "etc/os-release").apply {
            parentFile?.mkdirs()
            writeText("ID=ubuntu\nVERSION_ID=$ubuntuVersion\n")
        }
        File(fakeUbuntu, "etc/ssl/certs/ca-certificates.crt").apply {
            parentFile?.mkdirs()
            writeText("test certificate bundle\n")
        }
        val proot = File(binDir, "proot-distro")
        proot.writeText(
            """
            #!/bin/bash
            while [[ "${'$'}#" -gt 0 && "${'$'}1" != "--" ]]; do shift; done
            [[ "${'$'}#" -gt 0 ]] && shift
            if [[ "${'$'}1" == "/usr/bin/env" && "${'$'}2" == "bash" && "${'$'}3" == "-lc" ]]; then
              script="${'$'}(printf '%s' "${'$'}4" | sed \
                -e "s|/etc/os-release|${fakeUbuntu.absolutePath}/etc/os-release|g" \
                -e "s|/etc/ssl/certs/ca-certificates.crt|${fakeUbuntu.absolutePath}/etc/ssl/certs/ca-certificates.crt|g")"
              exec "${'$'}1" "${'$'}2" -c "${'$'}script"
            fi
            exec "${'$'}@"
            """.trimIndent(),
        )
        assertTrue(proot.setExecutable(true))

        val codex = File(binDir, "codex")
        val loginMarkerCommand = loginMarker?.let {
            "touch '${it.absolutePath.replace("'", "'\\''")}'"
        } ?: ":"
        codex.writeText(
            """
            #!/bin/bash
            case "${'$'}1:${'$'}{2:-}" in
              --version:) printf 'codex-cli $codexVersion\n' ;;
              login:status)
                $loginMarkerCommand
                ${if (loginHang) "sleep 30" else "exit ${if (loginReady) 0 else 1}"}
                ;;
              *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        assertTrue(codex.setExecutable(true))

        val python = File(binDir, "python3")
        python.writeText(
            """
            #!/bin/bash
            if [[ -n "${'$'}{CUSTOM_CODEX_TOKEN:-}" ]]; then
              printf 'ready\n'
            else
              printf '%s\n' '$pythonProbeResult'
            fi
            """.trimIndent(),
        )
        assertTrue(python.setExecutable(true))

        val sha256sum = File(binDir, "sha256sum")
        sha256sum.writeText("#!/bin/bash\nexit 0\n")
        assertTrue(sha256sum.setExecutable(true))

        val timeout = File(binDir, "timeout")
        timeout.writeText(
            """
            #!/bin/bash
            while [[ "${'$'}{1:-}" == --kill-after=* ]]; do shift; done
            duration="${'$'}1"
            shift
            case "${'$'}duration" in
              20s) delay=5.0 ;;
              5s) delay=1.0 ;;
              3s) delay=1.0 ;;
              *) delay=1.0 ;;
            esac
            exec /usr/bin/python3 -c 'import os,signal,subprocess,sys; limit=float(sys.argv[1]); child=subprocess.Popen(sys.argv[2:], start_new_session=True); result=0
try:
 result=child.wait(timeout=limit)
except subprocess.TimeoutExpired:
 os.killpg(child.pid, signal.SIGKILL); child.wait(); result=124
raise SystemExit(result)' "${'$'}delay" "${'$'}@"
            """.trimIndent(),
        )
        assertTrue(timeout.setExecutable(true))
        return binDir
    }

    private fun runDoctor(
        home: File,
        pathPrefix: File,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val host = runScript(
            script = EnvironmentProbe.TERMUX_DOCTOR_SCRIPT,
            home = home,
            pathPrefix = pathPrefix,
            environment = environment,
        )
        if (host.exitCode != 0 ||
            DoctorOutputParser.parse(host.output)["proot_distro"]?.status !=
            EnvironmentCheckStatus.READY
        ) {
            return host
        }
        val runtime = runScript(
            script = EnvironmentProbe.UBUNTU_DOCTOR_SCRIPT,
            home = home,
            pathPrefix = pathPrefix,
            environment = environment,
        )
        return ProcessResult(runtime.exitCode, host.output + runtime.output)
    }

    private fun runScript(
        script: String,
        home: File,
        pathPrefix: File,
        environment: Map<String, String>,
    ): ProcessResult {
        val process = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true)
        process.environment()["HOME"] = home.absolutePath
        process.environment()["PATH"] =
            pathPrefix.absolutePath + File.pathSeparator + "/usr/bin:/bin"
        process.environment().putAll(environment)
        val running = process.start()
        val output = running.inputStream.bufferedReader().readText()
        return ProcessResult(running.waitFor(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
