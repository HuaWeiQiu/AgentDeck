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

interface EnvironmentScanner {
    fun initialReport(): EnvironmentReport
    suspend fun scan(): EnvironmentReport
    fun allowExternalAppsFixCommand(): String
    fun errorReport(message: String): EnvironmentReport
}

@SuppressLint("SdCardPath")
class EnvironmentProbe(
    private val termux: TermuxGateway,
) : EnvironmentScanner {
    override fun initialReport(): EnvironmentReport = preflightReport(EnvironmentCheckStatus.UNKNOWN)

    override suspend fun scan(): EnvironmentReport {
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
                            commandResult.stderr.ifBlank { commandResult.stdout }.trim().takeLast(160),
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

    override fun allowExternalAppsFixCommand(): String = """
        mkdir -p ~/.termux
        if ! grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null; then
          printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
        fi
        termux-reload-settings
    """.trimIndent()

    override fun errorReport(message: String): EnvironmentReport = failedShellReport(message)

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
                        EnvironmentCheckStatus.ERROR
                    } else {
                        EnvironmentCheckStatus.BLOCKED
                    },
                    detail = if (allowExternalApps) {
                        "$safeMessage；请重试检测或检查 Termux 运行状态"
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
            CheckDefinition("codex_authenticated", "Codex 认证"),
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

            missing_wrapper=0
            for wrapper in \
              codex-ubuntu.sh \
              codex-app-server-start.sh \
              codex-app-server-bridge.py; do
              if [[ ! -x "${'$'}HOME/.agentdeck/wrappers/${'$'}wrapper" ]]; then
                missing_wrapper=${'$'}((missing_wrapper + 1))
              fi
            done
            if [[ "${'$'}missing_wrapper" -eq 0 ]] &&
              grep -Fq 'check_for_update_on_startup=false' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--sandbox danger-full-access' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--ask-for-approval on-request' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--instance-key' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh" &&
              grep -Fq 'check_for_update_on_startup=false' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-bridge.py" &&
              grep -Fq 'write_lease' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-bridge.py"; then
              emit codex_wrapper ready "Native Chat 启动组件已安装"
            elif [[ "${'$'}missing_wrapper" -eq 0 ]]; then
              emit codex_wrapper action_required "需要更新 Native Chat 启动组件"
            else
              emit codex_wrapper action_required "需要补齐 ${'$'}missing_wrapper 个启动组件"
            fi

            if command -v proot-distro >/dev/null 2>&1; then
              emit proot_distro ready "proot-distro 已安装"
              ubuntu_output="${'$'}(proot-distro login ubuntu -- /usr/bin/env bash -lc '
                set +e
                os_id="${'$'}(. /etc/os-release 2>/dev/null; printf "%s" "${'$'}{ID:-unknown}")"
                os_version="${'$'}(. /etc/os-release 2>/dev/null; printf "%s" "${'$'}{VERSION_ID:-unknown}")"
                os_id="${'$'}(printf "%s" "${'$'}os_id" | tr "\t\r\n" "   " | cut -c 1-40)"
                os_version="${'$'}(printf "%s" "${'$'}os_version" | tr "\t\r\n" "   " | cut -c 1-40)"

                if [[ "${'$'}os_id" != "ubuntu" || "${'$'}os_version" != "24.04" ]]; then
                  printf "ubuntu_installed\taction_required\t需要 Ubuntu 24.04，当前为 %s %s\n" "${'$'}os_id" "${'$'}os_version"
                  printf "codex_installed\tblocked\t先准备 Ubuntu 24.04\n"
                  printf "codex_authenticated\tblocked\t先准备 Ubuntu 24.04\n"
                  exit 0
                fi

                missing_dependencies=0
                for dependency in curl git gzip python3 sha256sum tar; do
                  command -v "${'$'}dependency" >/dev/null 2>&1 || missing_dependencies=${'$'}((missing_dependencies + 1))
                done
                [[ -s /etc/ssl/certs/ca-certificates.crt ]] || missing_dependencies=${'$'}((missing_dependencies + 1))
                if [[ "${'$'}missing_dependencies" -eq 0 ]]; then
                  printf "ubuntu_installed\tready\tUbuntu 24.04 与基础依赖可用\n"
                else
                  printf "ubuntu_installed\taction_required\tUbuntu 24.04 缺少 %s 项基础依赖\n" "${'$'}missing_dependencies"
                fi

                codex_path="${'$'}(command -v codex 2>/dev/null || true)"
                version=""
                version_supported=0
                if [[ -n "${'$'}codex_path" ]]; then
                  version="${'$'}(codex --version 2>/dev/null | head -n 1 | tr "\t\r\n" "   " | cut -c 1-160)"
                  if [[ "${'$'}version" =~ ([0-9]+)[.]([0-9]+)[.]([0-9]+) ]]; then
                    major="${'$'}{BASH_REMATCH[1]}"
                    minor="${'$'}{BASH_REMATCH[2]}"
                    patch="${'$'}{BASH_REMATCH[3]}"
                    if ((major > 0 || minor > 147 || (minor == 147 && patch >= 0))); then
                      version_supported=1
                    fi
                  fi
                fi
                if [[ -n "${'$'}codex_path" && -n "${'$'}version" && "${'$'}version_supported" -eq 1 ]]; then
                  printf "codex_installed\tready\t%s\n" "${'$'}version"

                  if codex login status >/dev/null 2>&1; then
                    printf "codex_authenticated\tready\tCodex 已确认认证状态\n"
                  elif [[ -n "${'$'}{OPENAI_API_KEY:-}" || -n "${'$'}{CODEX_ACCESS_TOKEN:-}" ]]; then
                    printf "codex_authenticated\tready\t已检测到认证环境变量\n"
                  else
                    auth_probe=""
                    if command -v python3 >/dev/null 2>&1; then
                      auth_probe="${'$'}(
                        python3 -c "import os,pathlib,tomllib; home=pathlib.Path(os.environ.get(\"CODEX_HOME\", pathlib.Path.home() / \".codex\")); path=home / \"config.toml\"; data=tomllib.loads(path.read_text()) if path.is_file() else {}; provider=str(data.get(\"model_provider\", \"openai\")); info=data.get(\"model_providers\", {}).get(provider, {}); env_key=info.get(\"env_key\") if isinstance(info, dict) else None; ready=(isinstance(env_key, str) and bool(os.environ.get(env_key))) or (isinstance(info, dict) and bool(info) and not env_key and info.get(\"requires_openai_auth\") is not True); print(\"ready\" if ready else \"configured\" if path.is_file() or (home / \"auth.json\").is_file() else \"missing\")" 2>/dev/null
                      )" || auth_probe=""
                    fi

                    if [[ "${'$'}auth_probe" == "ready" ]]; then
                      printf "codex_authenticated\tready\t已检测到可用 Provider 配置\n"
                    elif [[ "${'$'}auth_probe" == "configured" || -s "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/config.toml" || -s "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/auth.json" ]]; then
                      printf "codex_authenticated\taction_required\t已发现 Codex 配置，但没有可用凭据\n"
                    else
                      printf "codex_authenticated\taction_required\t未检测到登录或 API Key 配置\n"
                    fi
                  fi
                elif [[ -n "${'$'}codex_path" && -n "${'$'}version" ]]; then
                  printf "codex_installed\taction_required\t需要 Codex 0.147.0 或更高版本，当前为 %s\n" "${'$'}version"
                  printf "codex_authenticated\tblocked\t先更新 Codex CLI\n"
                elif [[ -n "${'$'}codex_path" ]]; then
                  printf "codex_installed\taction_required\tUbuntu 内的 codex 无法正常执行\n"
                  printf "codex_authenticated\tblocked\t先修复 Codex CLI\n"
                else
                  printf "codex_installed\taction_required\tUbuntu 内未找到 codex\n"
                  printf "codex_authenticated\tblocked\t先安装 Codex CLI\n"
                fi
              ' 2>/dev/null)"
              ubuntu_exit="${'$'}?"
              if [[ "${'$'}ubuntu_exit" -eq 0 ]]; then
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
