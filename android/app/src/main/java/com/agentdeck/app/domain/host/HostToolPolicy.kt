package com.agentdeck.app.domain.host

import com.agentdeck.app.domain.settings.ExperienceLevel

/**
 * Host 能力策略：默认拒绝。Codex「完全访问」不能开启任何 Host 能力。
 * [maxHostLevel] 由编译通道决定（Secure=1，Lab=4），见 ADR-0012。
 */
data class HostToolPolicy(
    val experienceLevel: ExperienceLevel,
    val workspaceEnabled: Boolean,
    val hasWorkspaceGrant: Boolean,
    val maxHostLevel: Int = 1,
    val intentEnabled: Boolean = false,
    val uiAutomationEnabled: Boolean = false,
    val privilegedEnabled: Boolean = false,
    val labRiskAccepted: Boolean = false,
) {
    fun evaluate(tool: HostToolName): HostToolResult.Denied? {
        if (tool.capability.level > maxHostLevel) {
            return HostToolResult.Denied(
                code = "host_channel_cap",
                userMessage = if (maxHostLevel <= 1) {
                    "安全版仅支持本机文件夹"
                } else {
                    "当前版本不支持该能力"
                },
            )
        }
        if (!experienceLevel.advancedEnabled) {
            return HostToolResult.Denied(
                code = "host_standard_mode",
                userMessage = "宿主能力仅在高级设置中提供",
            )
        }
        // L2+ 在 Lab 需要开发者层级 + 风险确认
        if (tool.capability.level >= 2) {
            if (experienceLevel != ExperienceLevel.DEVELOPER) {
                return HostToolResult.Denied(
                    code = "host_lab_developer_required",
                    userMessage = "请先开启开发者模式",
                )
            }
            if (!labRiskAccepted) {
                return HostToolResult.Denied(
                    code = "host_lab_risk_not_accepted",
                    userMessage = "请先确认 Lab 风险",
                )
            }
        }
        when (tool.capability) {
            HostCapability.WORKSPACE_FS -> {
                if (!workspaceEnabled) {
                    return HostToolResult.Denied(
                        code = "host_workspace_disabled",
                        userMessage = "本机工作区未开启",
                    )
                }
                if (!hasWorkspaceGrant) {
                    return HostToolResult.Denied(
                        code = "host_workspace_no_grant",
                        userMessage = "尚未选择并授权工作区文件夹",
                    )
                }
            }
            HostCapability.SHARE_INTENT -> {
                if (!intentEnabled) {
                    return HostToolResult.Denied(
                        code = "host_intent_disabled",
                        userMessage = "Lab Intent 协作未开启",
                    )
                }
            }
            HostCapability.UI_AUTOMATION -> {
                if (!uiAutomationEnabled) {
                    return HostToolResult.Denied(
                        code = "host_ui_disabled",
                        userMessage = "Lab 屏幕代理未开启",
                    )
                }
            }
            HostCapability.PRIVILEGED_SHELL -> {
                if (!privilegedEnabled) {
                    return HostToolResult.Denied(
                        code = "host_priv_disabled",
                        userMessage = "Lab 特权壳未开启",
                    )
                }
            }
        }
        return null
    }

    fun listEnabledCapabilities(): Set<HostCapability> {
        if (!experienceLevel.advancedEnabled) return emptySet()
        return buildSet {
            if (maxHostLevel >= 1 && workspaceEnabled && hasWorkspaceGrant) {
                add(HostCapability.WORKSPACE_FS)
            }
            if (maxHostLevel >= 2 && experienceLevel == ExperienceLevel.DEVELOPER &&
                labRiskAccepted && intentEnabled
            ) {
                add(HostCapability.SHARE_INTENT)
            }
            if (maxHostLevel >= 3 && experienceLevel == ExperienceLevel.DEVELOPER &&
                labRiskAccepted && uiAutomationEnabled
            ) {
                add(HostCapability.UI_AUTOMATION)
            }
            if (maxHostLevel >= 4 && experienceLevel == ExperienceLevel.DEVELOPER &&
                labRiskAccepted && privilegedEnabled
            ) {
                add(HostCapability.PRIVILEGED_SHELL)
            }
        }
    }
}
