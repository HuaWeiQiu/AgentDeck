package com.agentdeck.app.domain.env

import android.annotation.SuppressLint
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport

internal data class DoctorMarker(
    val id: String,
    val status: EnvironmentCheckStatus,
    val detail: String,
)

internal object DoctorOutputParser {
    fun parse(output: String): Map<String, DoctorMarker> {
        return output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val status = when (parts[1]) {
                "ready" -> EnvironmentCheckStatus.READY
                "action_required" -> EnvironmentCheckStatus.ACTION_REQUIRED
                "blocked" -> EnvironmentCheckStatus.BLOCKED
                "error" -> EnvironmentCheckStatus.ERROR
                else -> return@mapNotNull null
            }
            DoctorMarker(
                id = parts[0],
                status = status,
                detail = parts[2].trim().take(MAX_DETAIL_LENGTH),
            )
        }.associateBy { it.id }
    }

    private const val MAX_DETAIL_LENGTH = 240
}

@SuppressLint("SdCardPath")
class EnvironmentProbe(
    private val termux: TermuxGateway,
) {
    fun initialReport(): EnvironmentReport = preflightReport(EnvironmentCheckStatus.UNKNOWN)

    fun checkingReport(): EnvironmentReport = preflightReport(EnvironmentCheckStatus.CHECKING)

    suspend fun scan(): EnvironmentReport {
        val preflight = preflightReport(EnvironmentCheckStatus.CHECKING)
        if (preflight.check("termux_installed")?.ok != true ||
            preflight.check("termux_run_command_permission")?.ok != true
        ) {
            return preflight
        }

        val command = TermuxCommand(
            sessionName = "agentdeck-doctor",
            executable = "/data/data/com.termux/files/usr/bin/bash",
            args = listOf("-c", DOCTOR_SCRIPT),
            background = true,
            reuseExistingSession = false,
        )
        val result = termux.runCommandForResult(command)
        return result.fold(
            onSuccess = { commandResult ->
                if (!commandResult.commandSucceeded) {
                    failedShellReport(
                        "Doctor 退出码 ${commandResult.exitCode}: " +
                            commandResult.stderr.trim().take(160),
                    )
                } else if (commandResult.outputWasTruncated) {
                    failedShellReport("Doctor 输出被 Termux 截断，请重新检测")
                } else {
                    reportFromMarkers(DoctorOutputParser.parse(commandResult.stdout))
                }
            },
            onFailure = { error ->
                failedShellReport(error.message ?: "Termux Doctor 执行失败")
            },
        )
    }

    fun allowExternalAppsFixCommand(): String = """
        mkdir -p ~/.termux
        if ! grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null; then
          printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
        fi
        termux-reload-settings
    """.trimIndent()

    fun errorReport(message: String): EnvironmentReport = failedShellReport(message)

    private fun preflightReport(shellStatus: EnvironmentCheckStatus): EnvironmentReport {
        val installed = termux.isTermuxInstalled()
        val permission = installed && termux.hasRunCommandPermission()
        val shellDetail = when {
            !installed -> "先安装 Termux"
            !permission -> "先授予 RUN_COMMAND 权限"
            shellStatus == EnvironmentCheckStatus.CHECKING -> "正在通过 Termux 检测"
            else -> "尚未检测"
        }
        val effectiveShellStatus = when {
            !installed || !permission -> EnvironmentCheckStatus.BLOCKED
            else -> shellStatus
        }
        return EnvironmentReport(
            listOf(
                EnvironmentCheck(
                    id = "termux_installed",
                    label = "Termux",
                    status = if (installed) {
                        EnvironmentCheckStatus.READY
                    } else {
                        EnvironmentCheckStatus.ACTION_REQUIRED
                    },
                    detail = if (installed) "已检测到 com.termux" else "需要安装 F-Droid 版 Termux",
                ),
                EnvironmentCheck(
                    id = "termux_run_command_permission",
                    label = "RUN_COMMAND 权限",
                    status = when {
                        !installed -> EnvironmentCheckStatus.BLOCKED
                        permission -> EnvironmentCheckStatus.READY
                        else -> EnvironmentCheckStatus.ACTION_REQUIRED
                    },
                    detail = when {
                        !installed -> "先安装 Termux"
                        permission -> "已授权 AgentDeck 调用 Termux"
                        else -> "需要在系统权限对话框中授权"
                    },
                ),
            ) + SHELL_CHECKS.map { definition ->
                EnvironmentCheck(
                    id = definition.id,
                    label = definition.label,
                    status = effectiveShellStatus,
                    detail = shellDetail,
                )
            },
        )
    }

    private fun failedShellReport(message: String): EnvironmentReport {
        val local = preflightReport(EnvironmentCheckStatus.UNKNOWN).checks.take(2)
        val safeMessage = message.trim()
            .take(MAX_ERROR_DETAIL_LENGTH)
            .ifBlank { "Termux Doctor 未返回错误信息" }
        return EnvironmentReport(
            local + SHELL_CHECKS.map { definition ->
                val allowExternalApps = definition.id == "allow_external_apps"
                EnvironmentCheck(
                    id = definition.id,
                    label = definition.label,
                    status = if (allowExternalApps) {
                        EnvironmentCheckStatus.ACTION_REQUIRED
                    } else {
                        EnvironmentCheckStatus.BLOCKED
                    },
                    detail = if (allowExternalApps) {
                        "$safeMessage；请确认 allow-external-apps=true、Termux 版本和设置"
                    } else {
                        "Doctor 无法运行，等待 Termux 集成恢复"
                    },
                )
            },
        )
    }

    private fun reportFromMarkers(markers: Map<String, DoctorMarker>): EnvironmentReport {
        val local = preflightReport(EnvironmentCheckStatus.UNKNOWN).checks.take(2)
        return EnvironmentReport(
            local + SHELL_CHECKS.map { definition ->
                val marker = markers[definition.id]
                EnvironmentCheck(
                    id = definition.id,
                    label = definition.label,
                    status = marker?.status ?: EnvironmentCheckStatus.ERROR,
                    detail = marker?.detail ?: "Doctor 未返回此检查结果",
                )
            },
        )
    }

    private data class CheckDefinition(val id: String, val label: String)

    companion object {
        private val SHELL_CHECKS = listOf(
            CheckDefinition("allow_external_apps", "allow-external-apps"),
            CheckDefinition("proot_distro", "proot-distro"),
            CheckDefinition("ubuntu_installed", "Ubuntu"),
            CheckDefinition("codex_installed", "Codex CLI"),
            CheckDefinition("codex_authenticated", "Codex 登录"),
            CheckDefinition("codex_wrapper", "Codex 启动 wrapper"),
        )

        private const val MAX_ERROR_DETAIL_LENGTH = 180

        internal val DOCTOR_SCRIPT = """
            set -u
            emit() {
              printf '%s\t%s\t%s\n' "${'$'}1" "${'$'}2" "${'$'}3"
            }

            if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "${'$'}HOME/.termux/termux.properties" 2>/dev/null; then
              emit allow_external_apps ready "已启用"
            else
              emit allow_external_apps action_required "需要在 Termux 配置中启用并重载设置"
            fi

            if [[ -x "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" ]]; then
              emit codex_wrapper ready "wrapper 已安装"
            else
              emit codex_wrapper action_required "需要安装 codex-ubuntu.sh"
            fi

            if command -v proot-distro >/dev/null 2>&1; then
              emit proot_distro ready "proot-distro 已安装"
              ubuntu_output="${'$'}(proot-distro login ubuntu -- /usr/bin/env bash -c '
                if command -v codex >/dev/null 2>&1; then
                  version="${'$'}(codex --version 2>/dev/null | head -n 1 | tr "\t\r\n" "   " | cut -c 1-160)"
                  [[ -n "${'$'}version" ]] || version="codex 已安装"
                  printf "codex_installed\tready\t%s\n" "${'$'}version"
                  if codex login status >/dev/null 2>&1; then
                    printf "codex_authenticated\tready\t已登录\n"
                  else
                    printf "codex_authenticated\taction_required\t需要运行 codex login\n"
                  fi
                else
                  printf "codex_installed\taction_required\tUbuntu 内未找到 codex\n"
                  printf "codex_authenticated\tblocked\t先安装 Codex\n"
                fi
              ' 2>/dev/null)"
              ubuntu_exit="${'$'}?"
              if [[ "${'$'}ubuntu_exit" -eq 0 ]]; then
                emit ubuntu_installed ready "Ubuntu 可用"
                printf '%s\n' "${'$'}ubuntu_output"
              else
                emit ubuntu_installed action_required "未找到可用的 Ubuntu"
                emit codex_installed blocked "先安装 Ubuntu"
                emit codex_authenticated blocked "先安装 Ubuntu"
              fi
            else
              emit proot_distro action_required "Termux 内未找到 proot-distro"
              emit ubuntu_installed blocked "先安装 proot-distro"
              emit codex_installed blocked "先安装 Ubuntu"
              emit codex_authenticated blocked "先安装 Codex"
            fi
        """.trimIndent()
    }
}
