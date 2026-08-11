package com.agentdeck.app.domain.setup

import com.agentdeck.app.domain.env.EnvironmentScanner
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SetupCoordinator(
    private val scanner: EnvironmentScanner,
    private val installer: RecipeInstallation,
    private val scope: CoroutineScope,
    private val managedProviderReady: suspend () -> Boolean = { false },
    private val onReport: (EnvironmentReport) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SetupState(report = scanner.initialReport()))
    private var scanJob: Job? = null
    private var installJob: Job? = null
    private var lastScanCompletedAtMs: Long = 0L

    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    fun start() {
        scan()
    }

    @Synchronized
    fun scan(force: Boolean = false) {
        if (scanJob?.isActive == true || installJob?.isActive == true) return
        // 缓存窗口内的重复触发（如每次回前台的 ON_RESUME）直接复用上次结果，
        // 避免反复启动内嵌运行环境探测。
        if (!force && lastScanCompletedAtMs > 0 &&
            System.currentTimeMillis() - lastScanCompletedAtMs < SCAN_CACHE_WINDOW_MS
        ) {
            return
        }
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
            lastScanCompletedAtMs = System.currentTimeMillis()
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
            lastScanCompletedAtMs = System.currentTimeMillis()
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

    private suspend fun safeScan(): EnvironmentReport = try {
        val report = scanner.scan()
        val providerReady = try {
            managedProviderReady()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        report.withManagedProviderConnection(providerReady)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        scanner.errorReport(error.message ?: "环境检测意外失败")
    }

    companion object {
        private const val CODEX_RECIPE_ID = "recipe_codex"
        private const val MAX_ERROR_LENGTH = 480
        private const val SCAN_CACHE_WINDOW_MS = 45_000L
    }
}

internal fun EnvironmentReport.withManagedProviderConnection(ready: Boolean): EnvironmentReport {
    if (!ready || check("codex_authenticated")?.status == EnvironmentCheckStatus.READY) return this
    return copy(
        checks = checks.map { check ->
            if (check.id == "codex_authenticated") {
                check.copy(
                    status = EnvironmentCheckStatus.READY,
                    detail = "AgentDeck 模型服务已验证，将在会话启动时自动连接",
                )
            } else {
                check
            }
        },
    )
}

private fun com.agentdeck.app.domain.install.RecipeInstallProgress.userMessage(): String {
    val position = "${recipeIndex + 1}/$recipeCount"
    val action = when (phase) {
        com.agentdeck.app.domain.install.InstallPhase.PROBING -> "检测"
        com.agentdeck.app.domain.install.InstallPhase.DOWNLOADING -> "下载"
        com.agentdeck.app.domain.install.InstallPhase.VERIFYING_ARTIFACTS -> "校验下载"
        com.agentdeck.app.domain.install.InstallPhase.EXTRACTING -> "解压"
        com.agentdeck.app.domain.install.InstallPhase.INSTALLING -> "安装"
        com.agentdeck.app.domain.install.InstallPhase.INSTALLING_TOOLS -> "配置"
        com.agentdeck.app.domain.install.InstallPhase.VERIFYING -> "验证"
        com.agentdeck.app.domain.install.InstallPhase.VERIFYING_RUNTIME -> "运行自检"
        com.agentdeck.app.domain.install.InstallPhase.COMPLETE -> "完成"
    }
    return "$position · 正在$action $recipeName"
}
