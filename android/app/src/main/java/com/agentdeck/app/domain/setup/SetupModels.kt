package com.agentdeck.app.domain.setup

import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport

enum class SetupAction {
    SCAN,
    INSTALL_CODEX,
    CONFIGURE_CODEX_AUTH,
    UNSUPPORTED_DEVICE,
    READY,
}

data class SetupState(
    val report: EnvironmentReport,
    val isScanning: Boolean = false,
    val isInstalling: Boolean = false,
    val progress: RecipeInstallProgress? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val canStartChat: Boolean
        get() = report.canLaunchSessions

    val isReady: Boolean
        get() = report.allCriticalOk

    val action: SetupAction
        get() = SetupActionResolver.resolve(this)
}

object SetupActionResolver {
    fun resolve(state: SetupState): SetupAction {
        if (state.isScanning || state.isInstalling) return SetupAction.SCAN
        val report = state.report

        if (report.check("embedded_supported") == null) return SetupAction.SCAN
        if (report.needsScan("embedded_supported")) return SetupAction.SCAN
        if (!report.ready("embedded_supported")) return SetupAction.UNSUPPORTED_DEVICE
        if (report.needsScan("embedded_runtime")) return SetupAction.SCAN
        val runtimeIds = listOf(
            "embedded_runtime",
            "ubuntu_installed",
            "embedded_tools",
            "codex_installed",
            "codex_wrapper",
        )
        if (runtimeIds.any { report.needsScan(it) }) return SetupAction.SCAN
        if (runtimeIds.any { !report.ready(it) }) return SetupAction.INSTALL_CODEX
        if (report.needsScan("codex_authenticated")) return SetupAction.SCAN
        if (!report.ready("codex_authenticated")) return SetupAction.CONFIGURE_CODEX_AUTH
        return SetupAction.READY
    }

    private fun EnvironmentReport.ready(id: String): Boolean = check(id)?.ok == true

    private fun EnvironmentReport.needsScan(id: String): Boolean = when (check(id)?.status) {
        null,
        EnvironmentCheckStatus.UNKNOWN,
        EnvironmentCheckStatus.CHECKING,
        EnvironmentCheckStatus.ERROR,
        -> true
        else -> false
    }
}
