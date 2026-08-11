package com.agentdeck.app.domain.host

import com.agentdeck.app.domain.settings.ExperienceLevel

/**
 * Host 能力策略：默认拒绝。Codex「完全访问」不能开启任何 Host 能力。
 */
data class HostToolPolicy(
    val experienceLevel: ExperienceLevel,
    val workspaceEnabled: Boolean,
    val hasWorkspaceGrant: Boolean,
) {
    fun evaluate(tool: HostToolName): HostToolResult.Denied? {
        // L1 需要高级体验；标准模式一律拒绝宿主工具
        if (!experienceLevel.advancedEnabled) {
            return HostToolResult.Denied(
                code = "host_standard_mode",
                userMessage = "本机工作区仅在高级设置中提供",
            )
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
            HostCapability.SHARE_INTENT,
            HostCapability.UI_AUTOMATION,
            HostCapability.PRIVILEGED_SHELL,
            -> {
                return HostToolResult.Denied(
                    code = "host_capability_not_implemented",
                    userMessage = "该宿主能力尚未开放",
                )
            }
        }
        return null
    }

    fun listEnabledCapabilities(): Set<HostCapability> {
        if (!experienceLevel.advancedEnabled) return emptySet()
        return buildSet {
            if (workspaceEnabled && hasWorkspaceGrant) add(HostCapability.WORKSPACE_FS)
        }
    }
}
