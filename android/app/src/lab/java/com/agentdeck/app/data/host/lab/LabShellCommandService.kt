package com.agentdeck.app.data.host.lab

import com.agentdeck.app.data.host.lab.IShellCommandService
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 跑在 Shizuku server 进程（shell UID）内的用户服务。
 * 只暴露白名单外的通用 exec：超时与输出上限在此强制，调用方无法绕过。
 */
class LabShellCommandService : IShellCommandService.Stub() {

    private companion object {
        const val TIMEOUT_MS = 8_000L
        const val MAX_OUTPUT_CHARS = 64 * 1024
    }

    override fun exec(command: String): String {
        val result = JSONObject()
        val process = try {
            ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            result.put("error", (e.message ?: "spawn failed").take(200))
            return result.toString()
        }
        // 读线程 + waitFor：管道写满或命令卡死都不会拖垮调用方。
        val buffer = StringBuilder(MAX_OUTPUT_CHARS)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(2048)
                    while (r.read(chunk) >= 0 && buffer.length < MAX_OUTPUT_CHARS) {
                        buffer.append(chunk)
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            result.put("timeout", true)
            return result.toString()
        }
        reader.join(1_000)
        result.put("exit", process.exitValue())
        result.put("output", buffer.toString())
        return result.toString()
    }

    override fun destroy() {
        // daemon(false) 模式下由 Shizuku 回调；无持久资源需要清理。
    }
}
