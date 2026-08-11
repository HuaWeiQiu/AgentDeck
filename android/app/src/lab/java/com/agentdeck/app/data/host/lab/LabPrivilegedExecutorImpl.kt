package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabPrivilegedExecutor
import java.util.concurrent.TimeUnit

/**
 * Lab L4 骨架：在 App UID 下执行**严格白名单**命令。
 * 完整 Shizuku 提权后续可替换本实现；当前不引入额外依赖。
 */
class LabPrivilegedExecutorImpl : LabPrivilegedExecutor {
    override fun status(): HostToolResult = HostToolResult.Ok(
        mapOf(
            "mode" to "app_uid_whitelist",
            "shizuku" to "not_bundled",
            "note" to "仅允许 id/uname/getprop/pm list packages 等只读探测命令",
        ),
    )

    override fun shell(command: String): HostToolResult {
        val normalized = command.trim().replace(Regex("\\s+"), " ")
        if (normalized.length > 200) {
            return HostToolResult.Denied("host_cmd_too_long", "命令过长")
        }
        if (!isAllowlisted(normalized)) {
            return HostToolResult.Denied(
                "host_cmd_not_allowlisted",
                "命令不在白名单：仅允许 id、uname -a、getprop ro.build.version.release、pm list packages -3",
            )
        }
        return runCatching {
            val process = ProcessBuilder("sh", "-c", normalized)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching HostToolResult.Error("host_cmd_timeout", "命令超时")
            }
            val out = process.inputStream.bufferedReader().readText().take(4_000)
            HostToolResult.Ok(
                mapOf(
                    "exit" to process.exitValue().toString(),
                    "output" to out,
                ),
                truncated = out.length >= 4_000,
            )
        }.getOrElse {
            HostToolResult.Error("host_cmd_failed", it.message?.take(120) ?: "执行失败")
        }
    }

    private fun isAllowlisted(cmd: String): Boolean = when (cmd) {
        "id", "uname -a", "getprop ro.build.version.release", "pm list packages -3" -> true
        else -> false
    }
}
