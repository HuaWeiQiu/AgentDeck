package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabUiAutomationExecutor

/**
 * 无输入能力时的兜底：不提供观察与输入，只给出恢复指引，
 * 让上层 Agent 能把"为什么不能操作"转述给用户。
 */
internal object LabReadOnlyBackend : LabUiAutomationExecutor {

    private const val GUIDANCE =
        "当前无可用的屏幕输入后端。请任选其一恢复：1) 系统设置 → 无障碍 中开启 AgentDeck 屏幕代理；" +
            "2) 开发者选项 → 无线调试 配对并授权 Shizuku。"

    override fun currentApp(): HostToolResult = HostToolResult.Denied("host_input_readonly", GUIDANCE)

    override fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String): HostToolResult =
        HostToolResult.Denied("host_input_readonly", GUIDANCE)

    override fun click(snapshotId: String, nodeId: String): HostToolResult = denied()
    override fun clickText(text: String): HostToolResult = denied()
    override fun scroll(direction: String, snapshotId: String?, nodeId: String?): HostToolResult = denied()
    override fun back(): HostToolResult = denied()
    override fun home(): HostToolResult = denied()

    override fun setText(snapshotId: String, nodeId: String, value: String): HostToolResult = denied()

    override fun waitFor(packageName: String?, timeoutMs: Long): HostToolResult = denied()

    private fun denied() = HostToolResult.Denied("host_input_unavailable", GUIDANCE)
}
