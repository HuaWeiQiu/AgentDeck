package com.agentdeck.app.domain.env

import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram

class EmbeddedEnvironmentProbe(
    private val runtime: AgentRuntime,
) : EnvironmentScanner {
    override fun initialReport(): EnvironmentReport = report(EnvironmentCheckStatus.UNKNOWN)

    override suspend fun scan(): EnvironmentReport {
        val status = runtime.status()
        if (!status.supported) return unsupportedReport(status.detail)
        if (!status.ready) return report(EnvironmentCheckStatus.ACTION_REQUIRED)
        val result = runtime.runCommandForResult(
            RuntimeCommand(
                instanceId = "agentdeck-embedded-doctor",
                program = RuntimeProgram.HOST_SHELL,
                script = DOCTOR_SCRIPT,
                background = true,
                reuseExistingInstance = false,
            ),
            DOCTOR_TIMEOUT_MILLIS,
        ).getOrElse { error -> return errorReport(error.message ?: "内嵌运行环境检查失败") }
        if (!result.commandSucceeded || result.outputWasTruncated) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(240)
            return errorReport(detail.ifBlank { "内嵌运行环境检查未通过" })
        }
        val markers = DoctorOutputParser.parse(result.stdout)
        return EnvironmentReport(
            baseChecks(EnvironmentCheckStatus.READY) + RUNTIME_CHECKS.map { definition ->
                val marker = markers[definition.id]
                EnvironmentCheck(
                    definition.id,
                    definition.label,
                    marker?.status ?: EnvironmentCheckStatus.ERROR,
                    marker?.detail ?: "内嵌 Doctor 未返回该检查",
                )
            },
        )
    }

    override fun allowExternalAppsFixCommand(): String = ""

    override fun errorReport(message: String): EnvironmentReport = EnvironmentReport(
        baseChecks(EnvironmentCheckStatus.ERROR) + RUNTIME_CHECKS.map { definition ->
            EnvironmentCheck(
                definition.id,
                definition.label,
                EnvironmentCheckStatus.ERROR,
                message.trim().take(240).ifBlank { "内嵌运行环境检查失败" },
            )
        },
    )

    private fun report(runtimeStatus: EnvironmentCheckStatus): EnvironmentReport {
        val status = runtime.status()
        val supportedStatus = when {
            !status.supported -> EnvironmentCheckStatus.ACTION_REQUIRED
            else -> EnvironmentCheckStatus.READY
        }
        val installStatus = when {
            !status.supported -> EnvironmentCheckStatus.BLOCKED
            status.ready -> runtimeStatus
            else -> EnvironmentCheckStatus.ACTION_REQUIRED
        }
        return EnvironmentReport(
            listOf(
                EnvironmentCheck("embedded_supported", "设备兼容性", supportedStatus, status.detail),
                EnvironmentCheck(
                    "embedded_runtime",
                    "内嵌运行环境",
                    installStatus,
                    if (status.ready) "已安装 ${runtime.kind.name}" else "需要下载并验证本机运行组件",
                ),
            ) + RUNTIME_CHECKS.map { definition ->
                EnvironmentCheck(
                    definition.id,
                    definition.label,
                    if (status.ready) runtimeStatus else EnvironmentCheckStatus.BLOCKED,
                    if (status.ready) "等待检查" else "等待内嵌运行环境安装",
                )
            },
        )
    }

    private fun baseChecks(status: EnvironmentCheckStatus) = listOf(
        EnvironmentCheck("embedded_supported", "设备兼容性", EnvironmentCheckStatus.READY, "ARM64 可用"),
        EnvironmentCheck("embedded_runtime", "内嵌运行环境", status, "App 私有运行环境"),
    )

    private fun unsupportedReport(detail: String) = EnvironmentReport(
        listOf(
            EnvironmentCheck("embedded_supported", "设备兼容性", EnvironmentCheckStatus.ACTION_REQUIRED, detail),
            EnvironmentCheck("embedded_runtime", "内嵌运行环境", EnvironmentCheckStatus.BLOCKED, "当前设备不支持"),
        ) + RUNTIME_CHECKS.map { definition ->
            EnvironmentCheck(definition.id, definition.label, EnvironmentCheckStatus.BLOCKED, "当前设备不支持")
        },
    )

    private data class CheckDefinition(val id: String, val label: String)

    companion object {
        private const val DOCTOR_TIMEOUT_MILLIS = 15_000L
        private val RUNTIME_CHECKS = listOf(
            CheckDefinition("ubuntu_installed", "Ubuntu 24.04"),
            CheckDefinition("embedded_tools", "基础工具"),
            CheckDefinition("codex_installed", "Codex CLI"),
            CheckDefinition("codex_wrapper", "原生聊天组件"),
            CheckDefinition("codex_authenticated", "Codex 认证"),
        )

        private val DOCTOR_SCRIPT = """
            set +e
            emit() { printf '%s\t%s\t%s\n' "${'$'}1" "${'$'}2" "${'$'}3"; }
            version="${'$'}(. /etc/os-release 2>/dev/null && printf %s "${'$'}VERSION_ID")"
            if [[ "${'$'}version" == "24.04" ]]; then
              emit ubuntu_installed ready "Ubuntu ${'$'}version"
            else
              emit ubuntu_installed error "Ubuntu 版本异常"
            fi
            if command -v python3 >/dev/null && command -v git >/dev/null &&
              test -s /etc/ssl/certs/ca-certificates.crt; then
              emit embedded_tools ready "TLS、Git 与 Python 可用"
            else
              emit embedded_tools error "基础工具不完整"
            fi
            if command -v codex >/dev/null && codex --version 2>/dev/null | grep -Eq '0[.]147[.]0'; then
              emit codex_installed ready "Codex 0.147.0"
              emit codex_wrapper ready "App 内 app-server 启动器可用"
              if timeout --kill-after=1s 5s codex login status >/dev/null 2>&1; then
                emit codex_authenticated ready "已检测到 Codex 认证"
              else
                emit codex_authenticated action_required "可连接模型服务或登录 ChatGPT"
              fi
            else
              emit codex_installed error "Codex 未通过版本验证"
              emit codex_wrapper blocked "等待 Codex"
              emit codex_authenticated blocked "等待 Codex"
            fi
        """.trimIndent()
    }
}
