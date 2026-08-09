package com.agentdeck.app.data.runtime

import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class EmbeddedProotProcess(
    private val paths: EmbeddedRuntimePaths,
    private val rootfs: File = paths.activeRootfs,
) {
    fun start(
        script: String,
        workingDirectory: String = "/root",
        redirectOutput: File? = null,
    ): Process = startExecutable(
        command = listOf("/usr/bin/bash", "-lc", script),
        workingDirectory = workingDirectory,
        redirectOutput = redirectOutput,
    )

    fun startExecutable(
        command: List<String>,
        workingDirectory: String = "/root",
        redirectOutput: File? = null,
        guestEnvironment: Map<String, String> = emptyMap(),
    ): Process {
        val builder = ProcessBuilder(buildArguments(command, workingDirectory, guestEnvironment))
            .directory(paths.root)
        builder.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", paths.root.absolutePath)
            put("PROOT_LOADER", paths.prootLoader.absolutePath)
            put("PROOT_TMP_DIR", paths.tempDir.absolutePath)
        }
        if (redirectOutput != null) {
            builder.redirectErrorStream(true)
            builder.redirectOutput(redirectOutput)
        }
        return builder.start()
    }

    suspend fun execute(
        script: String,
        timeoutMillis: Long,
        workingDirectory: String = "/root",
    ): Result<RuntimeCommandResult> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            require(timeoutMillis > 0) { "运行超时必须大于 0" }
            process = start(script, workingDirectory)
            val running = process
            val stdout = CompletableFuture.supplyAsync { readBounded(running.inputStream.bufferedReader()) }
            val stderr = CompletableFuture.supplyAsync { readBounded(running.errorStream.bufferedReader()) }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (running.isAlive && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                running.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)
            }
            if (running.isAlive) {
                terminate(running)
                stdout.cancel(true)
                stderr.cancel(true)
                return@withContext Result.failure(
                    IllegalStateException("内嵌运行命令在 ${timeoutMillis / 1_000} 秒内没有返回结果"),
                )
            }
            val out = stdout.get(STREAM_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val err = stderr.get(STREAM_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            Result.success(
                RuntimeCommandResult(
                    stdout = out.text,
                    stderr = err.text,
                    exitCode = running.exitValue(),
                    stdoutOriginalLength = out.originalLength,
                    stderrOriginalLength = err.originalLength,
                ),
            )
        } catch (error: CancellationException) {
            process?.let(::terminate)
            throw error
        } catch (error: Exception) {
            process?.takeIf { it.isAlive }?.let(::terminate)
            Result.failure(error)
        }
    }

    private fun terminate(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    internal fun buildArguments(script: String, workingDirectory: String): List<String> =
        buildArguments(listOf("/usr/bin/bash", "-lc", script), workingDirectory, emptyMap())

    internal fun buildArguments(
        command: List<String>,
        workingDirectory: String,
        guestEnvironment: Map<String, String>,
    ): List<String> {
        require(rootfs.isDirectory) { "内嵌 Linux 运行环境尚未安装" }
        require(workingDirectory.startsWith('/') && '\u0000' !in workingDirectory) {
            "运行工作目录无效"
        }
        require(command.isNotEmpty() && command.all { '\u0000' !in it }) { "运行命令无效" }
        require(guestEnvironment.all { (key, value) ->
            key.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) && '\u0000' !in value
        }) { "运行环境变量无效" }
        return buildList {
            add(paths.proot.absolutePath)
            add("-L")
            add("--link2symlink")
            add("--sysvipc")
            add("--kill-on-exit")
            add("-0")
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            add("--bind=${paths.stateDir.absolutePath}:/run/agentdeck")
            add("--bind=${paths.tempDir.absolutePath}:/tmp")
            add("-w")
            add(workingDirectory)
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("USER=root")
            add("LOGNAME=root")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            add("TMPDIR=/tmp")
            add("DEBIAN_FRONTEND=noninteractive")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            guestEnvironment.forEach { (key, value) -> add("$key=$value") }
            addAll(command)
        }
    }

    private fun readBounded(reader: BufferedReader): BoundedText {
        val retained = StringBuilder()
        val buffer = CharArray(8_192)
        var total = 0
        try {
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                total += count
                if (retained.length < MAX_OUTPUT_CHARS) {
                    retained.append(buffer, 0, minOf(count, MAX_OUTPUT_CHARS - retained.length))
                }
            }
        } catch (_: IOException) {
            // The process may close streams while being stopped; preserve captured output.
        }
        return BoundedText(retained.toString(), total)
    }

    private data class BoundedText(val text: String, val originalLength: Int)

    companion object {
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val STOP_GRACE_MILLIS = 1_000L
        private const val POLL_MILLIS = 200L
        private const val STREAM_DRAIN_TIMEOUT_MILLIS = 2_000L
    }
}
