package com.agentdeck.app.domain.setup

import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport

enum class SetupAction {
    SCAN,
    INSTALL_TERMUX,
    GRANT_PERMISSION,
    ENABLE_EXTERNAL_APPS,
    INSTALL_CODEX,
    CONFIGURE_CODEX_AUTH,
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

        if (report.needsScan("termux_installed")) return SetupAction.SCAN
        if (!report.ready("termux_installed")) return SetupAction.INSTALL_TERMUX

        if (report.needsScan("termux_run_command_permission")) return SetupAction.SCAN
        if (!report.ready("termux_run_command_permission")) return SetupAction.GRANT_PERMISSION

        if (report.needsScan("allow_external_apps")) return SetupAction.SCAN
        if (!report.ready("allow_external_apps")) return SetupAction.ENABLE_EXTERNAL_APPS

        val runtimeIds = listOf(
            "proot_distro",
            "ubuntu_installed",
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
