package com.agentdeck.app.data.runtime

import android.content.Context
import android.os.Process
import android.system.Os
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import com.agentdeck.app.domain.runtime.RuntimeKind
import com.agentdeck.app.domain.runtime.RuntimeProgram
import com.agentdeck.app.domain.runtime.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class EmbeddedProotRuntime(
    context: Context,
    private val paths: EmbeddedRuntimePaths = EmbeddedRuntimePaths(context),
) : AgentRuntime {
    private val app = context.applicationContext
    override val kind = RuntimeKind.EMBEDDED_PROOT
    private val processes = ConcurrentHashMap<String, OwnedProcess>()

    init {
        reapStaleProcesses()
    }

    override fun status(): RuntimeStatus {
        val supported = EmbeddedRuntimeManifest.deviceSupported()
        val packaged = paths.proot.isFile && paths.prootLoader.isFile && paths.packagedTalloc.isFile
        val installed = supported && packaged && runCatching { paths.isReady() }.getOrDefault(false)
        return RuntimeStatus(
            kind = kind,
            supported = supported,
            installed = installed,
            ready = installed,
            detail = when {
                !supported -> "当前测试版仅支持 ARM64 或 x86_64 Android 设备"
                !packaged -> "APK 缺少内嵌运行组件"
                !installed -> "内嵌 Codex 运行环境尚未准备"
                else -> "内嵌 Codex 运行环境可用"
            },
        )
    }

    override fun openConsole(): Boolean = false

    override fun openInstallPage(): Boolean = false

    override fun openAppSettings(): Boolean = false

    override fun runCommand(command: RuntimeCommand): Result<Unit> = when (command.program) {
        RuntimeProgram.CODEX_APP_SERVER -> runCatching {
            val options = AppServerOptions.parse(command.args)
            require(options.stop) { "启动 app-server 时必须等待结构化连接结果" }
            stop(options.instanceKey).getOrThrow()
        }
        RuntimeProgram.CODEX_TERMINAL,
        RuntimeProgram.CLAUDE_TERMINAL,
        -> Result.failure(UnsupportedOperationException("内嵌运行环境暂不提供完整终端界面"))
        RuntimeProgram.HOST_SHELL -> Result.failure(
            IllegalArgumentException("Shell 命令必须等待结构化结果"),
        )
    }

    override suspend fun runCommandForResult(
        command: RuntimeCommand,
        timeoutMillis: Long,
    ): Result<RuntimeCommandResult> {
        if (!status().ready) return Result.failure(IllegalStateException(status().detail))
        paths.ensureHostLayout()
        return when (command.program) {
            RuntimeProgram.HOST_SHELL -> EmbeddedProotProcess(paths).execute(
                script = requireNotNull(command.script) { "Shell 请求缺少脚本" },
                timeoutMillis = timeoutMillis,
                workingDirectory = command.workDir ?: "/root",
            )
            RuntimeProgram.CODEX_APP_SERVER -> launchAppServer(command.args, timeoutMillis)
            RuntimeProgram.CODEX_TERMINAL,
            RuntimeProgram.CLAUDE_TERMINAL,
            -> Result.failure(UnsupportedOperationException("内嵌运行环境暂不提供完整终端界面"))
        }
    }

    override fun stop(instanceId: String): Result<Unit> = runCatching {
        require(INSTANCE_KEY_PATTERN.matches(instanceId)) { "Codex 实例标识无效" }
        val marker = marker(instanceId)
        val ownedPid = readOwnedPid(instanceId, marker)
        val owned = processes.remove(instanceId)
        ownedPid?.let { terminateProcessTree(it, marker) }
        if (owned != null) {
            terminate(owned.process)
        }
        if (owned != null) EmbeddedRuntimeService.release(app)
        runtimeFiles(instanceId).forEach(File::delete)
    }

    private suspend fun launchAppServer(
        args: List<String>,
        timeoutMillis: Long,
    ): Result<RuntimeCommandResult> = withContext(Dispatchers.IO) {
        var options: AppServerOptions? = null
        try {
            options = AppServerOptions.parse(args)
            require(!options.stop) { "停止 app-server 不返回连接信息" }
            stop(options.instanceKey).getOrThrow()
            paths.ensureHostLayout()

            val token = randomToken()
            val credentialToken = options.provider?.let { randomToken() }
            val tokenFile = tokenFile(options.instanceKey)
            val credentialTokenFile = credentialTokenFile(options.instanceKey)
            tokenFile.writeText("$token\n")
            Os.chmod(tokenFile.absolutePath, 0b110000000)
            if (credentialToken != null) {
                credentialTokenFile.writeText("$credentialToken\n")
                Os.chmod(credentialTokenFile.absolutePath, 0b110000000)
            }

            val workspaceResult = EmbeddedProotProcess(paths).execute(
                script = "mkdir -p -- ${shellQuote(options.cwd)}",
                timeoutMillis = 10_000,
            ).getOrThrow()
            check(workspaceResult.commandSucceeded) { "无法创建 Codex 工作目录" }

            val log = logFile(options.instanceKey).apply {
                parentFile?.mkdirs()
                writeText("")
                Os.chmod(absolutePath, 0b110000000)
            }
            EmbeddedRuntimeService.acquire(app)
            val process = try {
                EmbeddedProotProcess(paths).startExecutable(
                    command = buildCodexCommand(options),
                    workingDirectory = options.cwd,
                    redirectOutput = log,
                    guestEnvironment = mapOf("AGENTDECK_INSTANCE" to marker(options.instanceKey)),
                    skillSnapshotKey = options.skillSnapshotKey,
                )
            } catch (error: Exception) {
                EmbeddedRuntimeService.release(app)
                throw error
            }
            leaseFile(options.instanceKey).writeText("${processPid(process)}\n")
            processes[options.instanceKey] = OwnedProcess(process, log)

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var port: Int? = null
            while (System.nanoTime() < deadline && port == null) {
                port = LISTEN_PATTERN.find(log.readText().takeLast(MAX_START_LOG_CHARS))
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (port == null) {
                    if (!process.isAlive) {
                        val detail = log.readText().trim().takeLast(400).ifBlank { "未返回错误信息" }
                        cleanup(options.instanceKey)
                        error("Codex app-server 启动失败：$detail")
                    }
                    delay(100)
                }
            }
            val readyPort = port ?: run {
                cleanup(options.instanceKey)
                error("Codex app-server 启动超时")
            }
            require(readyPort in 1..65_535) { "Codex app-server 返回了无效端口" }
            val json = JSONObject()
                .put("port", readyPort)
                .put("token", token)
                .put("pid", processPid(process))
            credentialToken?.let { json.put("credential_token", it) }
            Result.success(
                RuntimeCommandResult(
                    stdout = json.toString() + "\n",
                    stderr = "",
                    exitCode = 0,
                ),
            )
        } catch (error: CancellationException) {
            options?.instanceKey?.let(::cleanup)
            throw error
        } catch (error: Exception) {
            options?.instanceKey?.let(::cleanup)
            Result.failure(error)
        }
    }

    private fun buildCodexCommand(options: AppServerOptions): List<String> = buildList {
        add("/usr/local/bin/codex")
        addAll(listOf("-c", "check_for_update_on_startup=false"))
        options.provider?.let { provider ->
            val helperArgs = listOf(
                "/usr/local/lib/agentdeck/codex-provider-token.py",
                "--port",
                provider.credentialBrokerPort.toString(),
                "--token-file",
                "/run/agentdeck/${credentialTokenFile(options.instanceKey).name}",
                "--credential-ref",
                provider.credentialRef,
            ).joinToString(prefix = "[", postfix = "]") { tomlQuote(it) }
            addAll(listOf("-c", "model_provider=${tomlQuote(provider.providerId)}"))
            addAll(listOf("-c", "model=${tomlQuote(provider.model)}"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.name=${tomlQuote("AgentDeck")}"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.base_url=${tomlQuote(provider.baseUrl)}"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.wire_api=${tomlQuote("responses")}"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.auth.command=${tomlQuote("/usr/bin/python3")}"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.auth.args=$helperArgs"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.auth.timeout_ms=5000"))
            addAll(listOf("-c", "model_providers.${provider.providerId}.auth.refresh_interval_ms=0"))
        }
        addAll(
            listOf(
                "app-server",
                "--listen",
                "ws://127.0.0.1:0",
                "--ws-auth",
                "capability-token",
                "--ws-token-file",
                "/run/agentdeck/${tokenFile(options.instanceKey).name}",
            ),
        )
    }

    private fun cleanup(instanceKey: String) {
        val marker = marker(instanceKey)
        val ownedPid = readOwnedPid(instanceKey, marker)
        val owned = processes.remove(instanceKey)
        ownedPid?.let { terminateProcessTree(it, marker) }
        owned?.let { terminate(it.process) }
        if (owned != null) EmbeddedRuntimeService.release(app)
        runtimeFiles(instanceKey).forEach(File::delete)
    }

    private fun terminate(process: java.lang.Process) {
        process.destroy()
        if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun readOwnedPid(instanceKey: String, marker: String): Int? {
        val pid = leaseFile(instanceKey).takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull()
            ?: return null
        if (pid <= 0) return null
        val arguments = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000')
        }.getOrNull() ?: return null
        return pid.takeIf { hasOwnedMarker(arguments, marker) }
    }

    private fun terminateProcessTree(pid: Int, marker: String) {
        val tree = ownedProcessTree(pid, marker)
        if (tree.isEmpty()) return
        tree.asReversed().forEach { childPid ->
            runCatching { Process.killProcess(childPid) }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS)
        while (System.nanoTime() < deadline && tree.any(::isSameUidProcess)) {
            Thread.sleep(STOP_POLL_MILLIS)
        }
    }

    private fun ownedProcessTree(pid: Int, marker: String): List<Int> {
        if (!isOwnedPid(pid, marker)) return emptyList()
        val result = LinkedHashSet<Int>()
        fun visit(current: Int) {
            if (result.size >= MAX_PROCESS_TREE_SIZE || !result.add(current)) return
            directChildren(current).forEach(::visit)
        }
        visit(pid)
        return result.toList()
    }

    private fun directChildren(parentPid: Int): List<Int> = runCatching {
        File("/proc").listFiles().orEmpty().asSequence()
            .mapNotNull { it.name.toIntOrNull() }
            .filter { it > 0 && isSameUidProcess(it) }
            .filter { pid ->
                val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
                status != null && parseParentPid(status) == parentPid
            }
            .take(MAX_PROCESS_TREE_SIZE)
            .toList()
    }.getOrDefault(emptyList())

    private fun isSameUidProcess(pid: Int): Boolean = runCatching {
        Os.stat("/proc/$pid").st_uid == Process.myUid()
    }.getOrDefault(false)

    private fun isOwnedPid(pid: Int, marker: String): Boolean {
        if (pid <= 0) return false
        val arguments = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000')
        }.getOrNull() ?: return false
        return hasOwnedMarker(arguments, marker)
    }

    private fun reapStaleProcesses() {
        val leases = paths.stateDir.listFiles { file ->
            file.isFile && LEASE_FILE_PATTERN.matches(file.name)
        }.orEmpty()
        leases.forEach { lease ->
            val key = LEASE_FILE_PATTERN.matchEntire(lease.name)?.groupValues?.get(1)
                ?: return@forEach
            val marker = marker(key)
            readOwnedPid(key, marker)?.let { pid -> runCatching { terminateProcessTree(pid, marker) } }
            runtimeFiles(key).forEach(File::delete)
        }
    }

    private fun processPid(process: java.lang.Process): Int {
        val publicPid = runCatching {
            process.javaClass.getMethod("pid").invoke(process) as Long
        }.getOrNull()
        if (publicPid != null) return publicPid.toInt()
        return runCatching {
            process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
        }.getOrElse { error("Android 未提供子进程 PID") }
    }

    private fun runtimeFiles(instanceKey: String) = listOf(
        leaseFile(instanceKey),
        tokenFile(instanceKey),
        credentialTokenFile(instanceKey),
        logFile(instanceKey),
    )

    private fun leaseFile(key: String) = File(paths.stateDir, "app-server.$key.pid")
    private fun tokenFile(key: String) = File(paths.stateDir, "app-server.$key.token")
    private fun credentialTokenFile(key: String) = File(paths.stateDir, "app-server.$key.credential-token")
    private fun logFile(key: String) = File(paths.stateDir, "app-server.$key.log")
    private fun marker(key: String) = "agentdeck-app-server-$key"

    private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun tomlQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append('"')
    }

    private data class OwnedProcess(val process: java.lang.Process, val log: File)

    internal data class AppServerOptions(
        val cwd: String,
        val instanceKey: String,
        val stop: Boolean,
        val provider: ProviderOptions?,
        val skillSnapshotKey: String?,
    ) {
        companion object {
            fun parse(args: List<String>): AppServerOptions {
                var distro = "ubuntu"
                var cwd = "/root/projects/default"
                var instanceKey = ""
                var stop = false
                var skillSnapshotKey: String? = null
                val managed = linkedMapOf<String, String>()
                var index = 0
                while (index < args.size) {
                    val option = args[index]
                    if (option == "--stop") {
                        stop = true
                        index += 1
                        continue
                    }
                    require(index + 1 < args.size) { "$option 缺少参数" }
                    val value = args[index + 1]
                    when (option) {
                        "--distro" -> distro = value
                        "--cwd" -> cwd = value
                        "--instance-key" -> instanceKey = value
                        "--provider-id",
                        "--base-url",
                        "--model",
                        "--credential-ref",
                        "--credential-broker-port",
                        -> managed[option] = value
                        "--skill-snapshot-key" -> skillSnapshotKey = value
                        else -> error("不支持的 app-server 参数: $option")
                    }
                    index += 2
                }
                require(distro == "ubuntu") { "内嵌运行环境只支持 Ubuntu" }
                require(cwd.startsWith('/') && cwd.length <= 1_024 && cwd.none(Char::isISOControl)) {
                    "Codex 工作目录无效"
                }
                require(INSTANCE_KEY_PATTERN.matches(instanceKey)) { "Codex 实例标识无效" }
                require(skillSnapshotKey == null || skillSnapshotKey == instanceKey) {
                    "Skill 快照与 app-server 实例不匹配"
                }
                val provider = if (managed.isEmpty()) null else ProviderOptions.from(managed)
                return AppServerOptions(cwd, instanceKey, stop, provider, skillSnapshotKey)
            }
        }
    }

    internal data class ProviderOptions(
        val providerId: String,
        val baseUrl: String,
        val model: String,
        val credentialRef: String,
        val credentialBrokerPort: Int,
    ) {
        companion object {
            fun from(values: Map<String, String>): ProviderOptions {
                require(values.size == 5) { "模型服务运行参数不完整" }
                val providerId = values.getValue("--provider-id")
                val baseUrl = values.getValue("--base-url")
                val model = values.getValue("--model")
                val credentialRef = values.getValue("--credential-ref")
                val port = values.getValue("--credential-broker-port").toIntOrNull()
                require(providerId.matches(Regex("agentdeck_[a-f0-9]{16}"))) { "模型服务标识无效" }
                require(
                    baseUrl.startsWith("https://") && baseUrl.length <= 2_048 &&
                        baseUrl.none { it.isWhitespace() || it.isISOControl() },
                ) { "模型服务地址无效" }
                require(model.isNotBlank() && model.length <= 512 && model.none(Char::isISOControl)) { "模型 ID 无效" }
                require(credentialRef.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "模型凭据引用无效" }
                require(port in 1..65_535) { "凭据代理端口无效" }
                return ProviderOptions(providerId, baseUrl, model, credentialRef, requireNotNull(port))
            }
        }
    }

    companion object {
        private val INSTANCE_KEY_PATTERN = Regex("[a-f0-9]{1,16}")
        private val LISTEN_PATTERN = Regex("ws://127[.]0[.]0[.]1:([0-9]{1,5})")
        private val LEASE_FILE_PATTERN = Regex("app-server[.]([a-f0-9]{1,16})[.]pid")
        private const val STOP_GRACE_MILLIS = 1_000L
        private const val STOP_POLL_MILLIS = 50L
        private const val MAX_PROCESS_TREE_SIZE = 128
        private const val MAX_START_LOG_CHARS = 64 * 1024
    }
}

internal fun hasOwnedMarker(arguments: List<String>, marker: String): Boolean =
    "AGENTDECK_INSTANCE=$marker" in arguments

internal fun parseParentPid(status: String): Int? = status.lineSequence()
    .firstOrNull { it.startsWith("PPid:") }
    ?.substringAfter(':')
    ?.trim()
    ?.toIntOrNull()
