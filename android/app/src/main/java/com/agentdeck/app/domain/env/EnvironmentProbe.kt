package com.agentdeck.app.domain.env

import android.annotation.SuppressLint
import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram

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
    private val runtime: AgentRuntime,
) : EnvironmentScanner {
    override fun initialReport(): EnvironmentReport = preflightReport(EnvironmentCheckStatus.UNKNOWN)

    override suspend fun scan(): EnvironmentReport {
        val preflight = preflightReport(EnvironmentCheckStatus.CHECKING)
        if (preflight.check("termux_installed")?.ok != true ||
            preflight.check("termux_run_command_permission")?.ok != true
        ) {
            return preflight
        }

        val host = runDoctorPhase(
            sessionName = "agentdeck-doctor-host",
            script = TERMUX_DOCTOR_SCRIPT,
            timeoutMillis = HOST_RESULT_TIMEOUT_MILLIS,
        )
        if (host.error != null) return failedHostReport(host.error)
        if (host.markers["proot_distro"]?.status != EnvironmentCheckStatus.READY) {
            return reportFromMarkers(host.markers)
        }

        val runtime = runDoctorPhase(
            sessionName = "agentdeck-doctor-runtime",
            script = UBUNTU_DOCTOR_SCRIPT,
            timeoutMillis = RUNTIME_RESULT_TIMEOUT_MILLIS,
        )
        val markers = host.markers + runtime.markers
        return reportFromMarkers(markers, runtime.error)
    }

    override fun allowExternalAppsFixCommand(): String = """
        mkdir -p ~/.termux
        if ! grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null; then
          printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
        fi
        termux-reload-settings
    """.trimIndent()

    override fun errorReport(message: String): EnvironmentReport = failedHostReport(message)

    private suspend fun runDoctorPhase(
        sessionName: String,
        script: String,
        timeoutMillis: Long,
    ): DoctorPhaseResult {
        val command = RuntimeCommand(
            instanceId = sessionName,
            program = RuntimeProgram.HOST_SHELL,
            script = script,
            background = true,
            reuseExistingInstance = false,
        )
        return runtime.runCommandForResult(command, timeoutMillis).fold(
            onSuccess = { commandResult ->
                val markers = DoctorOutputParser.parse(commandResult.stdout)
                val error = when {
                    !commandResult.commandSucceeded -> {
                        "退出码 ${commandResult.exitCode}: " +
                            commandResult.stderr.ifBlank { commandResult.stdout }
                                .trim().takeLast(160)
                    }
                    commandResult.outputWasTruncated -> "输出被 Termux 截断，请重新检测"
                    markers[PHASE_ERROR_MARKER]?.status == EnvironmentCheckStatus.ERROR -> {
                        markers.getValue(PHASE_ERROR_MARKER).detail
                    }
                    else -> null
                }
                DoctorPhaseResult(markers - PHASE_ERROR_MARKER, error)
            },
            onFailure = { error ->
                DoctorPhaseResult(
                    markers = emptyMap(),
                    error = error.message ?: "Termux Doctor 执行失败",
                )
            },
        )
    }

    private fun preflightReport(shellStatus: EnvironmentCheckStatus): EnvironmentReport {
        val runtimeStatus = runtime.status()
        val installed = runtimeStatus.installed
        val permission = installed && runtimeStatus.ready
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
        val backgroundStatus = when {
            !installed || !permission -> EnvironmentCheckStatus.BLOCKED
            shellStatus == EnvironmentCheckStatus.CHECKING -> EnvironmentCheckStatus.CHECKING
            else -> EnvironmentCheckStatus.UNKNOWN
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
                EnvironmentCheck(
                    id = "termux_background_execution",
                    label = "Termux 后台执行",
                    status = backgroundStatus,
                    detail = when {
                        !installed -> "先安装 Termux"
                        !permission -> "先授予 RUN_COMMAND 权限"
                        shellStatus == EnvironmentCheckStatus.CHECKING -> "正在验证后台命令能否返回"
                        else -> "等待行为检测"
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

    private fun failedHostReport(message: String): EnvironmentReport {
        val local = preflightReport(EnvironmentCheckStatus.UNKNOWN).checks.take(2)
        val safeMessage = message.trim()
            .take(MAX_ERROR_DETAIL_LENGTH)
            .ifBlank { "Termux Doctor 未返回错误信息" }
        val backgroundRestricted = message.contains("没有返回结果") ||
            message.contains("超时") ||
            message.contains("timed out", ignoreCase = true)
        val backgroundCheck = EnvironmentCheck(
            id = "termux_background_execution",
            label = "Termux 后台执行",
            status = if (backgroundRestricted) {
                EnvironmentCheckStatus.ACTION_REQUIRED
            } else {
                EnvironmentCheckStatus.ERROR
            },
            detail = if (backgroundRestricted) {
                "Termux 在 AgentDeck 前台时未响应；请在耗电管理中允许后台高耗电或设为不限制"
            } else {
                "后台行为检测失败：$safeMessage"
            },
        )
        return EnvironmentReport(
            local + backgroundCheck + SHELL_CHECKS.map { definition ->
                val hostCheck = definition.id in HOST_CHECK_IDS
                EnvironmentCheck(
                    id = definition.id,
                    label = definition.label,
                    status = if (hostCheck) {
                        EnvironmentCheckStatus.ERROR
                    } else {
                        EnvironmentCheckStatus.BLOCKED
                    },
                    detail = if (hostCheck) {
                        "Termux 主机检查失败：$safeMessage"
                    } else {
                        "等待 Termux 主机检查恢复"
                    },
                )
            },
        )
    }

    private fun reportFromMarkers(
        markers: Map<String, DoctorMarker>,
        runtimeError: String? = null,
    ): EnvironmentReport {
        val local = preflightReport(EnvironmentCheckStatus.UNKNOWN).checks.take(2) +
            EnvironmentCheck(
                id = "termux_background_execution",
                label = "Termux 后台执行",
                status = EnvironmentCheckStatus.READY,
                detail = "后台 Doctor 命令已成功返回",
            )
        return EnvironmentReport(
            local + SHELL_CHECKS.map { definition ->
                val marker = markers[definition.id]
                val runtimeFallback = if (marker == null && runtimeError != null) {
                    runtimeFailureCheck(definition, runtimeError)
                } else {
                    null
                }
                EnvironmentCheck(
                    id = definition.id,
                    label = definition.label,
                    status = marker?.status ?: runtimeFallback?.status
                        ?: EnvironmentCheckStatus.ERROR,
                    detail = marker?.detail ?: runtimeFallback?.detail
                        ?: "Doctor 未返回此检查结果",
                )
            },
        )
    }

    private fun runtimeFailureCheck(
        definition: CheckDefinition,
        message: String,
    ): EnvironmentCheck {
        val safeMessage = message.trim()
            .take(MAX_ERROR_DETAIL_LENGTH)
            .ifBlank { "Ubuntu 运行时检查失败" }
        val ubuntuCheck = definition.id == "ubuntu_installed"
        return EnvironmentCheck(
            id = definition.id,
            label = definition.label,
            status = if (ubuntuCheck) EnvironmentCheckStatus.ERROR else EnvironmentCheckStatus.BLOCKED,
            detail = if (ubuntuCheck) {
                "Ubuntu 运行时检查失败：$safeMessage"
            } else {
                "等待 Ubuntu 运行时检查恢复"
            },
        )
    }

    private data class DoctorPhaseResult(
        val markers: Map<String, DoctorMarker>,
        val error: String?,
    )

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

        private val HOST_CHECK_IDS = setOf(
            "allow_external_apps",
            "proot_distro",
            "codex_wrapper",
        )

        private const val MAX_ERROR_DETAIL_LENGTH = 180
        private const val HOST_RESULT_TIMEOUT_MILLIS = 10_000L
        private const val RUNTIME_RESULT_TIMEOUT_MILLIS = 25_000L
        private const val PHASE_ERROR_MARKER = "doctor_phase"

        internal val TERMUX_DOCTOR_SCRIPT = """
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
              codex-provider-token.py; do
              if [[ ! -x "${'$'}HOME/.agentdeck/wrappers/${'$'}wrapper" ]]; then
                missing_wrapper=${'$'}((missing_wrapper + 1))
              fi
            done
            if [[ "${'$'}missing_wrapper" -eq 0 ]] &&
              grep -Fq 'check_for_update_on_startup=false' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--sandbox danger-full-access' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--ask-for-approval "${'$'}approval_policy"' "${'$'}HOME/.agentdeck/wrappers/codex-ubuntu.sh" &&
              grep -Fq -- '--instance-key' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh" &&
              test -x "${'$'}HOME/.agentdeck/wrappers/codex-provider-token.py" &&
              grep -Fq 'START_CONTRACT_VERSION=7' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh" &&
              grep -Fq 'api_key_b64' "${'$'}HOME/.agentdeck/wrappers/codex-provider-token.py" &&
              grep -Fq -- '--listen ws://127.0.0.1:0' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh" &&
              grep -Fq -- '--ws-auth capability-token' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh" &&
              grep -Fq -- '--ws-token-file' "${'$'}HOME/.agentdeck/wrappers/codex-app-server-start.sh"; then
              bridge_detail="Native Chat WebSocket 组件已安装"
              for state_file in "${'$'}HOME/.agentdeck/runtime"/app-server.*.state; do
                if [[ -f "${'$'}state_file" && ! -L "${'$'}state_file" ]]; then
                  bridge_state="${'$'}(tail -n 1 -- "${'$'}state_file" 2>/dev/null | cut -f 2 | tr -cd 'A-Za-z0-9_:/.-' | cut -c 1-120)"
                  [[ -n "${'$'}bridge_state" ]] && bridge_detail="${'$'}bridge_detail；上次状态 ${'$'}bridge_state"
                fi
              done
              emit codex_wrapper ready "${'$'}bridge_detail"
            elif [[ "${'$'}missing_wrapper" -eq 0 ]]; then
              emit codex_wrapper action_required "需要更新 Native Chat 启动组件"
            else
              emit codex_wrapper action_required "需要补齐 ${'$'}missing_wrapper 个启动组件"
            fi

            if command -v proot-distro >/dev/null 2>&1; then
              emit proot_distro ready "proot-distro 已安装"
            else
              emit proot_distro action_required "Termux 内未找到 proot-distro"
              emit ubuntu_installed blocked "先安装 proot-distro"
              emit codex_installed blocked "先安装 Ubuntu"
              emit codex_authenticated blocked "先安装 Codex"
            fi
        """.trimIndent()

        internal val UBUNTU_DOCTOR_SCRIPT = """
            set +e
            emit() {
              printf '%s\t%s\t%s\n' "${'$'}1" "${'$'}2" "${'$'}3"
            }

            timeout --kill-after=2s 20s proot-distro login ubuntu -- /usr/bin/env bash -lc '
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
                for dependency in curl git gzip python3 sha256sum tar timeout; do
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
                version_exit=0
                if [[ -n "${'$'}codex_path" ]]; then
                  version_raw="${'$'}(timeout --kill-after=1s 5s codex --version 2>/dev/null)"
                  version_exit="${'$'}?"
                  version="${'$'}(printf '%s' "${'$'}version_raw" | head -n 1 | tr "\t\r\n" "   " | cut -c 1-160)"
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

                  if [[ -n "${'$'}{OPENAI_API_KEY:-}" || -n "${'$'}{CODEX_ACCESS_TOKEN:-}" ]]; then
                    printf "codex_authenticated\tready\t已检测到认证环境变量\n"
                  else
                    auth_probe=""
                    auth_probe_exit=0
                    if command -v python3 >/dev/null 2>&1; then
                      auth_probe="${'$'}(
                        timeout --kill-after=1s 3s python3 -c "import os,pathlib,tomllib; home=pathlib.Path(os.environ.get(\"CODEX_HOME\", pathlib.Path.home() / \".codex\")); path=home / \"config.toml\"; data=tomllib.loads(path.read_text()) if path.is_file() else {}; provider=str(data.get(\"model_provider\", \"openai\")); info=data.get(\"model_providers\", {}).get(provider, {}); env_key=info.get(\"env_key\") if isinstance(info, dict) else None; ready=(isinstance(env_key, str) and bool(os.environ.get(env_key))) or (provider != \"openai\" and isinstance(info, dict) and bool(info) and not env_key and info.get(\"requires_openai_auth\") is not True); print(\"ready\" if ready else \"configured\" if path.is_file() or (home / \"auth.json\").is_file() else \"missing\")" 2>/dev/null
                      )"
                      auth_probe_exit="${'$'}?"
                    fi

                    if [[ "${'$'}auth_probe" == "ready" ]]; then
                      printf "codex_authenticated\tready\t已检测到可用 Provider 配置\n"
                    else
                      timeout --kill-after=1s 5s codex login status >/dev/null 2>&1
                      login_exit="${'$'}?"
                      if [[ "${'$'}login_exit" -eq 0 ]]; then
                        printf "codex_authenticated\tready\tCodex 已确认认证状态\n"
                      elif [[ "${'$'}login_exit" -eq 124 || "${'$'}login_exit" -eq 137 ]]; then
                        printf "codex_authenticated\terror\t官方认证检查超时，未阻塞其它环境检测\n"
                      elif [[ "${'$'}auth_probe_exit" -eq 124 || "${'$'}auth_probe_exit" -eq 137 ]]; then
                        printf "codex_authenticated\terror\tProvider 配置检查超时\n"
                      elif [[ "${'$'}auth_probe" == "configured" || -s "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/config.toml" || -s "${'$'}{CODEX_HOME:-${'$'}HOME/.codex}/auth.json" ]]; then
                        printf "codex_authenticated\taction_required\t已发现 Codex 配置，但没有可用凭据\n"
                      else
                        printf "codex_authenticated\taction_required\t未检测到登录或 API Key 配置\n"
                      fi
                    fi
                  fi
                elif [[ -n "${'$'}codex_path" && ("${'$'}version_exit" -eq 124 || "${'$'}version_exit" -eq 137) ]]; then
                  printf "codex_installed\terror\tCodex 版本检查超时\n"
                  printf "codex_authenticated\tblocked\t先修复 Codex CLI\n"
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
              ' 2>/dev/null
            ubuntu_exit="${'$'}?"
            case "${'$'}ubuntu_exit" in
              0) ;;
              124|137) emit doctor_phase error "Ubuntu 检测超过 20 秒，已终止检测进程" ;;
              *) emit doctor_phase error "Ubuntu 无法启动（退出码 ${'$'}ubuntu_exit）" ;;
            esac
        """.trimIndent()
    }
}
