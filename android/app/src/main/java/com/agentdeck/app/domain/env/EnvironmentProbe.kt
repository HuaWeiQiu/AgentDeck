package com.agentdeck.app.domain.env

import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentReport

/**
 * Lightweight environment checks available without shelling into Termux.
 * Deep checks (proot/ubuntu/codex) are reported as "需在 Termux 内确认" for skeleton.
 */
class EnvironmentProbe(
    private val termux: TermuxGateway,
) {
    fun scan(): EnvironmentReport {
        val termuxInstalled = termux.isTermuxInstalled()
        val hasPermission = termux.hasRunCommandPermission()

        val checks = listOf(
            EnvironmentCheck(
                id = "termux_installed",
                label = "Termux 已安装",
                ok = termuxInstalled,
                detail = if (termuxInstalled) {
                    "检测到 com.termux"
                } else {
                    "请安装 F-Droid 版 Termux"
                },
            ),
            EnvironmentCheck(
                id = "termux_run_command_permission",
                label = "RUN_COMMAND 权限",
                ok = hasPermission,
                detail = if (hasPermission) {
                    "已授予 com.termux.permission.RUN_COMMAND"
                } else {
                    "启动时系统会请求；若失败请在系统设置中授予"
                },
            ),
            EnvironmentCheck(
                id = "allow_external_apps",
                label = "allow-external-apps",
                ok = termuxInstalled,
                detail = "需在 Termux 中设置 ~/.termux/termux.properties → allow-external-apps=true（骨架阶段未自动探测）",
            ),
            EnvironmentCheck(
                id = "proot_ubuntu",
                label = "proot-distro + ubuntu",
                ok = false,
                detail = "骨架阶段：请在商店安装基础环境后于 Termux 验证",
            ),
            EnvironmentCheck(
                id = "codex_installed",
                label = "Codex CLI",
                ok = false,
                detail = "骨架阶段：安装配方后在 ubuntu 内 command -v codex",
            ),
        )
        return EnvironmentReport(checks)
    }

    fun allowExternalAppsFixCommand(): String = """
        mkdir -p ~/.termux
        if ! grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null; then
          printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
        fi
        termux-reload-settings
    """.trimIndent()
}
