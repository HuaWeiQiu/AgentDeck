package com.agentdeck.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.host.HostWriteApprovalMode
import com.agentdeck.app.domain.host.WorkspaceGrant
import com.agentdeck.app.domain.settings.ConversationMode
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.runtime.RuntimeCliStatus
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel : ViewModel() {
    private val setup = ServiceLocator.setup

    val state: StateFlow<SetupState> = setup.state
    val conversationMode: StateFlow<ConversationMode> =
        ServiceLocator.experienceSettings.conversationMode
    val experienceLevel: StateFlow<ExperienceLevel> = ServiceLocator.experienceSettings.level
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        ServiceLocator.experienceSettings.codexPermissionLevel
    val hostWorkspaceEnabled: StateFlow<Boolean> =
        ServiceLocator.experienceSettings.hostWorkspaceEnabled
    val hostWriteApprovalMode: StateFlow<HostWriteApprovalMode> =
        ServiceLocator.experienceSettings.hostWriteApprovalMode
    val workspaceGrants: StateFlow<List<WorkspaceGrant>> = ServiceLocator.workspaceGrants.grants
    val isLabBuild: Boolean = BuildConfig.HOST_LAB
    val labRiskAccepted: StateFlow<Boolean> = ServiceLocator.experienceSettings.labRiskAccepted
    val labIntentEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labIntentEnabled
    val labUiEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labUiEnabled
    val labPrivEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labPrivEnabled
    private val backup = ServiceLocator.conversationBackup
    private val lastBackupExportAtState = MutableStateFlow<Long?>(null)
    val lastBackupExportAt: StateFlow<Long?> = lastBackupExportAtState.asStateFlow()
    // Never walk rootfs on the main thread / field init — that freezes the Settings tab.
    private val runtimeStatusesState = MutableStateFlow<List<RuntimeCliStatus>>(emptyList())
    val runtimeStatuses: StateFlow<List<RuntimeCliStatus>> = runtimeStatusesState.asStateFlow()
    private val runtimeActionBusyState = MutableStateFlow(false)
    val runtimeActionBusy: StateFlow<Boolean> = runtimeActionBusyState.asStateFlow()
    private val runtimeActionMessageState = MutableStateFlow<String?>(null)
    val runtimeActionMessage: StateFlow<String?> = runtimeActionMessageState.asStateFlow()
    @Volatile private var lastRuntimeRefreshAtMs: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            lastBackupExportAtState.value = backup.lastExportAtEpochMs()
        }
    }

    fun setAdvancedEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            if (enabled) ExperienceLevel.ADVANCED else ExperienceLevel.STANDARD,
        )
    }

    /** 连续开启高级后，Lab 需要开发者模式才能开 L2+。 */
    fun setDeveloperEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            when {
                enabled -> ExperienceLevel.DEVELOPER
                ServiceLocator.experienceSettings.level.value == ExperienceLevel.STANDARD ->
                    ExperienceLevel.STANDARD
                else -> ExperienceLevel.ADVANCED
            },
        )
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        ServiceLocator.experienceSettings.setCodexPermissionLevel(level)
    }

    fun setHostWorkspaceEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(enabled)
    }

    fun setHostWriteApprovalMode(mode: HostWriteApprovalMode) {
        ServiceLocator.experienceSettings.setHostWriteApprovalMode(mode)
    }

    fun setLabRiskAccepted(accepted: Boolean) {
        ServiceLocator.experienceSettings.setLabRiskAccepted(accepted)
    }

    fun setLabIntentEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabIntentEnabled(enabled)
    }

    fun setLabUiEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabUiEnabled(enabled)
    }

    fun setLabPrivEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabPrivEnabled(enabled)
    }

    fun addWorkspaceGrant(treeUri: Uri, displayName: String) {
        ServiceLocator.workspaceGrants.addGrant(treeUri, displayName)
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(true)
    }

    fun revokeWorkspaceGrant(grantId: String) {
        ServiceLocator.workspaceGrants.revoke(grantId)
    }

    fun suggestedBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        return "agentdeck-backup-" + stamp + ".json"
    }

    fun exportConversations(context: Context, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.writeExport(context, uri)
                lastBackupExportAtState.value = backup.lastExportAtEpochMs()
                "已导出 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）"
            }.getOrElse { error -> "导出失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }

    /**
     * Snapshot runtime install status on a background thread.
     * [force] bypasses the short cache (after install/delete).
     */
    fun refreshRuntimes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force &&
            runtimeStatusesState.value.isNotEmpty() &&
            now - lastRuntimeRefreshAtMs < RUNTIME_STATUS_CACHE_MS
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = runCatching {
                ServiceLocator.runtimeInventory.statuses()
            }.getOrDefault(emptyList())
            lastRuntimeRefreshAtMs = System.currentTimeMillis()
            runtimeStatusesState.value = snapshot
        }
    }

    fun deleteCodexRuntime() {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.runtimeInventory.deleteCodex()
            val snapshot = runCatching {
                ServiceLocator.runtimeInventory.statuses()
            }.getOrDefault(emptyList())
            lastRuntimeRefreshAtMs = System.currentTimeMillis()
            runtimeStatusesState.value = snapshot
            withContext(Dispatchers.Main.immediate) {
                setup.scan(force = true)
            }
        }
    }

    fun deleteDshRuntime(includeUserHome: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.runtimeInventory.deleteDsh(includeUserHome = includeUserHome)
            val snapshot = runCatching {
                ServiceLocator.runtimeInventory.statuses()
            }.getOrDefault(emptyList())
            lastRuntimeRefreshAtMs = System.currentTimeMillis()
            runtimeStatusesState.value = snapshot
        }
    }

    fun deletePiRuntime(includeUserHome: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            ServiceLocator.runtimeInventory.deletePi(includeUserHome = includeUserHome)
            val snapshot = runCatching {
                ServiceLocator.runtimeInventory.statuses()
            }.getOrDefault(emptyList())
            lastRuntimeRefreshAtMs = System.currentTimeMillis()
            runtimeStatusesState.value = snapshot
        }
    }

    fun preparePi() {
        if (runtimeActionBusyState.value) return
        runtimeActionBusyState.value = true
        runtimeActionMessageState.value = "开始准备 pi…"
        android.util.Log.i(TAG, "preparePi: start")
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    ServiceLocator.runtimeInventory.installPi { progress ->
                        val label = when (progress.phase) {
                            com.agentdeck.app.domain.install.InstallPhase.PROBING -> "检查环境…"
                            com.agentdeck.app.domain.install.InstallPhase.DOWNLOADING -> {
                                val done = progress.bytesDone ?: 0L
                                val total = progress.bytesTotal ?: 0L
                                if (total > 0L) {
                                    "下载 Node " + formatMb(done) + " / " + formatMb(total)
                                } else {
                                    "正在下载 Node…"
                                }
                            }
                            com.agentdeck.app.domain.install.InstallPhase.VERIFYING_ARTIFACTS -> "校验下载文件…"
                            com.agentdeck.app.domain.install.InstallPhase.EXTRACTING -> "准备 Node…"
                            com.agentdeck.app.domain.install.InstallPhase.INSTALLING_TOOLS ->
                                if (progress.prefersDomesticSources == true) {
                                    "安装 pi（国内源）…"
                                } else {
                                    "安装 pi（需联网）…"
                                }
                            com.agentdeck.app.domain.install.InstallPhase.VERIFYING_RUNTIME -> "验证 pi --help…"
                            com.agentdeck.app.domain.install.InstallPhase.COMPLETE -> "完成"
                            else -> "处理中…"
                        }
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            runtimeActionMessageState.value = label
                        }
                    }
                }
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "preparePi crashed", error)
                Result.failure(error)
            }
            refreshRuntimes(force = true)
            runtimeActionBusyState.value = false
            runtimeActionMessageState.value = result.fold(
                onSuccess = {
                    android.util.Log.i(TAG, "preparePi ok: $it")
                    it
                },
                onFailure = { error ->
                    android.util.Log.e(TAG, "preparePi failed", error)
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: error.cause?.message?.takeIf { it.isNotBlank() }
                        ?: error.javaClass.simpleName
                    "准备 pi 失败：$detail"
                },
            )
        }
    }

    /** D2 smoke: run pi --help under proot and surface the first line. */
    fun verifyPiHelp() {
        if (runtimeActionBusyState.value) return
        runtimeActionBusyState.value = true
        runtimeActionMessageState.value = "运行 pi --help…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.runtimeInventory.smokePiHelp()
            }
            runtimeActionBusyState.value = false
            runtimeActionMessageState.value = result.fold(
                onSuccess = { "pi 正常：$it" },
                onFailure = { error ->
                    "pi 验证失败：" + (error.message ?: error.javaClass.simpleName)
                },
            )
        }
    }

    fun prepareDsh() {
        if (runtimeActionBusyState.value) return
        runtimeActionBusyState.value = true
        runtimeActionMessageState.value = "开始准备 dsh…"
        android.util.Log.e(TAG, "prepareDsh: start")
        viewModelScope.launch {
            val result = try {
                // installDsh does OkHttp downloads; never run on Main.
                withContext(Dispatchers.IO) {
                    ServiceLocator.runtimeInventory.installDsh { progress ->
                        val label = when (progress.phase) {
                            com.agentdeck.app.domain.install.InstallPhase.PROBING -> "检查环境…"
                            com.agentdeck.app.domain.install.InstallPhase.DOWNLOADING -> {
                                val done = progress.bytesDone ?: 0L
                                val total = progress.bytesTotal ?: 0L
                                if (total > 0L) {
                                    "下载组件 " + formatMb(done) + " / " + formatMb(total)
                                } else {
                                    "正在下载组件…"
                                }
                            }
                            com.agentdeck.app.domain.install.InstallPhase.VERIFYING_ARTIFACTS -> "校验下载文件…"
                            com.agentdeck.app.domain.install.InstallPhase.EXTRACTING -> "解压组件…"
                            com.agentdeck.app.domain.install.InstallPhase.INSTALLING_TOOLS ->
                                if (progress.prefersDomesticSources == true) {
                                    "安装 dsh 组件（国内源）…"
                                } else {
                                    "安装 dsh 组件（需联网）…"
                                }
                            com.agentdeck.app.domain.install.InstallPhase.VERIFYING_RUNTIME -> "验证安装…"
                            com.agentdeck.app.domain.install.InstallPhase.COMPLETE -> "完成"
                            else -> "处理中…"
                        }
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            runtimeActionMessageState.value = label
                        }
                    }
                }
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "prepareDsh crashed", error)
                Result.failure(error)
            }
            refreshRuntimes(force = true)
            runtimeActionBusyState.value = false
            runtimeActionMessageState.value = result.fold(
                onSuccess = {
                    android.util.Log.e(TAG, "prepareDsh ok: $it")
                    it
                },
                onFailure = { error ->
                    android.util.Log.e(TAG, "prepareDsh failed", error)
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: error.cause?.message?.takeIf { it.isNotBlank() }
                        ?: error.javaClass.simpleName
                    "准备 dsh 失败：$detail"
                },
            )
        }
    }

    /**
     * Opens dsh Web UI. Launches managed process if installed, else may attach to
     * an already-running 127.0.0.1:3080 for manual smoke tests.
     */
    fun openDshWeb(onDone: (Result<String>) -> Unit) {
        if (runtimeActionBusyState.value) return
        runtimeActionBusyState.value = true
        runtimeActionMessageState.value = "正在启动网页（首次约 1 分钟）…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.agentdeck.app.data.runtime.NativeRuntimeBudget.onDshOpen()
                ServiceLocator.runtimeInventory.dshSupervisor().open()
                    .map { session -> session.url }
            }
            runtimeActionBusyState.value = false
            if (result.isFailure) {
                val detail = result.exceptionOrNull()?.message ?: "无法打开"
                runtimeActionMessageState.value = detail
                android.util.Log.e(TAG, "openDshWeb failed: $detail", result.exceptionOrNull())
            } else {
                runtimeActionMessageState.value = null
                android.util.Log.e(TAG, "openDshWeb ok: ${result.getOrNull()}")
            }
            onDone(result)
        }
    }

    fun stopDshWeb() {
        ServiceLocator.runtimeInventory.dshSupervisor().stop()
    }

    private fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        return ((bytes + 1024L * 512L) / (1024L * 1024L)).toString() + " MB"
    }

    companion object {
        private const val TAG = "AgentDeckRuntime"
        /** Avoid re-walking multi-hundred-MB rootfs trees on every return. */
        private const val RUNTIME_STATUS_CACHE_MS = 15_000L
    }

    fun importLocalBackup(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.importLocalCopy()
                "已从本机备份恢复 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）"
            }.getOrElse { error -> "恢复失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }

    fun importConversations(context: Context, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.importFrom(context, uri)
                "已恢复 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）。模型密钥需要重新验证。"
            }.getOrElse { error -> "恢复失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }
}
