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
) {
    fun evaluate(tool: HostToolName): HostToolResult.Denied? {
        if (tool.capability.level > maxHostLevel) {
            return HostToolResult.Denied(
                code = "host_channel_cap",
                userMessage = if (maxHostLevel <= 1) {
                    "当前为安全版，仅支持本机工作区（L1）"
                } else {
                    "当前通道不支持该宿主能力等级"
                },
            )
        }
        // L1 需要高级体验；标准模式一律拒绝宿主工具
        if (!experienceLevel.advancedEnabled) {
            return HostToolResult.Denied(
                code = "host_standard_mode",
                userMessage = "宿主能力仅在高级设置中提供",
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
                // Lab 通道才可能到此；执行器未接线时仍拒绝
                return HostToolResult.Denied(
                    code = "host_capability_not_implemented",
                    userMessage = "该 Lab 宿主能力尚未接线",
                )
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
        }
    }
}
