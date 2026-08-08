package com.agentdeck.app.domain.setup

import android.annotation.SuppressLint
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.env.EnvironmentScanner
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.model.EnvironmentReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("SdCardPath") // Fixed cross-app path required by Termux RUN_COMMAND.
class SetupCoordinator(
    private val scanner: EnvironmentScanner,
    private val installer: RecipeInstallation,
    private val termux: TermuxGateway,
    private val scope: CoroutineScope,
    private val onReport: (EnvironmentReport) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SetupState(report = scanner.initialReport()))
    private var scanJob: Job? = null
    private var installJob: Job? = null

    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    fun start() {
        scan()
    }

    @Synchronized
    fun scan() {
        if (scanJob?.isActive == true || installJob?.isActive == true) return
        mutableState.update {
            it.copy(
                isScanning = true,
                message = "正在检测 Codex 运行环境",
                error = null,
            )
        }
        scanJob = scope.launch {
            val report = safeScan()
            runCatching { onReport(report) }
            mutableState.update {
                it.copy(
                    report = report,
                    isScanning = false,
                    message = null,
                )
            }
        }
    }

    @Synchronized
    fun installCodex() {
        if (scanJob?.isActive == true || installJob?.isActive == true) return
        if (mutableState.value.action != SetupAction.INSTALL_CODEX) return

        mutableState.update {
            it.copy(
                isInstalling = true,
                progress = null,
                message = "正在检查现有环境",
                error = null,
            )
        }
        installJob = scope.launch {
            val result = try {
                installer.install(CODEX_RECIPE_ID) { progress ->
                    mutableState.update {
                        it.copy(
                            progress = progress,
                            message = progress.userMessage(),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

            val report = safeScan()
            runCatching { onReport(report) }
            mutableState.update { current ->
                result.fold(
                    onSuccess = { success ->
                        current.copy(
                            report = report,
                            isInstalling = false,
                            progress = null,
                            message = success,
                            error = null,
                        )
                    },
                    onFailure = { error ->
                        current.copy(
                            report = report,
                            isInstalling = false,
                            message = null,
                            error = error.message?.trim()?.take(MAX_ERROR_LENGTH)
                                ?: "Codex 环境安装失败",
                        )
                    },
                )
            }
        }
    }

    fun openTermuxInstallPage(): Boolean = termux.openTermuxInstallPage()

    fun openTermux(): Boolean = termux.openTermux()

    fun openTermuxAppSettings(): Boolean = termux.openTermuxAppSettings()

    fun allowExternalAppsFixCommand(): String = scanner.allowExternalAppsFixCommand()

    fun startCodexAuthentication(): Result<Unit> {
        if (mutableState.value.action != SetupAction.CONFIGURE_CODEX_AUTH) {
            return Result.failure(IllegalStateException("当前环境无需或尚未进入 Codex 认证步骤"))
        }
        val command = TermuxCommand(
            sessionName = "agentdeck-codex-auth",
            executable = CODEX_WRAPPER,
            args = listOf(
                "--distro",
                "ubuntu",
                "--cwd",
                "/root/projects/default",
                "--bin",
                "bash",
                "--",
                "-lc",
                CODEX_AUTH_SETUP_SCRIPT,
            ),
            background = false,
            reuseExistingSession = true,
        )
        return termux.runCommand(command).fold(
            onSuccess = {
                if (termux.openTermux()) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("认证助手已启动，但无法打开 Termux"))
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun safeScan(): EnvironmentReport = try {
        scanner.scan()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        scanner.errorReport(error.message ?: "环境检测意外失败")
    }

    companion object {
        private const val CODEX_RECIPE_ID = "recipe_codex"
        private const val CODEX_WRAPPER =
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-ubuntu.sh"
        private const val MAX_ERROR_LENGTH = 240

        private val CODEX_AUTH_SETUP_SCRIPT = """
            set +u

            auth_ready=0
            if [[ -n "${'$'}{OPENAI_API_KEY:-}" || -n "${'$'}{CODEX_ACCESS_TOKEN:-}" ]]; then
              auth_ready=1
            elif command -v python3 >/dev/null 2>&1 &&
              timeout --kill-after=1s 3s python3 -c 'import os,pathlib,tomllib; home=pathlib.Path(os.environ.get("CODEX_HOME", pathlib.Path.home() / ".codex")); path=home / "config.toml"; data=tomllib.loads(path.read_text()) if path.is_file() else {}; provider=str(data.get("model_provider", "openai")); info=data.get("model_providers", {}).get(provider, {}); env_key=info.get("env_key") if isinstance(info, dict) else None; ready=(isinstance(env_key, str) and bool(os.environ.get(env_key))) or (provider != "openai" and isinstance(info, dict) and bool(info) and not env_key and info.get("requires_openai_auth") is not True); raise SystemExit(0 if ready else 1)' 2>/dev/null; then
              auth_ready=1
            elif timeout --kill-after=1s 5s codex login status >/dev/null 2>&1; then
              auth_ready=1
            fi

            if [[ "${'$'}auth_ready" -eq 1 ]]; then
              printf '\nCodex 已检测到可用认证，无需重复配置。\n'
              printf '返回 AgentDeck 后点击重新检测即可。\n'
              exec bash -l
            fi

            default_choice=1
            if command -v python3 >/dev/null 2>&1 &&
              python3 -c 'import os, pathlib, tomllib; path=pathlib.Path(os.environ.get("CODEX_HOME", pathlib.Path.home() / ".codex")) / "config.toml"; data=tomllib.loads(path.read_text()) if path.is_file() else {}; method=str(data.get("forced_login_method", "")).lower(); raise SystemExit(0 if method in {"api", "api_key"} else 1)' 2>/dev/null; then
              default_choice=2
            fi

            printf '\nCodex 认证\n\n'
            printf '1  ChatGPT 设备登录\n'
            printf '2  API Key（输入时不会显示）\n\n'
            read -r -p "选择 [1/2]（默认 ${'$'}default_choice）: " choice
            choice="${'$'}{choice:-${'$'}default_choice}"

            case "${'$'}choice" in
              2)
                read -r -s -p '请输入 API Key: ' codex_api_key
                printf '\n'
                if [[ -n "${'$'}codex_api_key" ]]; then
                  printf '%s' "${'$'}codex_api_key" | codex login --with-api-key
                else
                  printf '未输入 API Key，已取消。\n'
                fi
                unset codex_api_key
                ;;
              *)
                codex login --device-auth
                ;;
            esac

            printf '\n完成后返回 AgentDeck 点击重新检测。\n'
            exec bash -l
        """.trimIndent()
    }
}

private fun com.agentdeck.app.domain.install.RecipeInstallProgress.userMessage(): String {
    val position = "${recipeIndex + 1}/$recipeCount"
    val action = when (phase) {
        com.agentdeck.app.domain.install.InstallPhase.PROBING -> "检测"
        com.agentdeck.app.domain.install.InstallPhase.INSTALLING -> "安装"
        com.agentdeck.app.domain.install.InstallPhase.VERIFYING -> "验证"
        com.agentdeck.app.domain.install.InstallPhase.COMPLETE -> "完成"
    }
    return "$position · 正在$action $recipeName"
}
