package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabUiAutomationExecutor

/**
 * 三级后端路由器：Shell(Shizuku) > Accessibility > ReadOnly。
 * 每次调用时按可用性选择活跃后端；LabHostLoader 经 LabUiAutomationHolder 读到本对象。
 */
object LabInputBackendRouter : LabUiAutomationExecutor {

    fun activeBackend(): String = when {
        LabShellBackend.isAvailable() -> "shell"
        LabAccessibilityRegistry.a11yExecutor != null -> "accessibility"
        else -> "read_only"
    }

    override fun currentApp(): HostToolResult = active().currentApp()

    override fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String): HostToolResult =
        active().snapshot(maxChars, sessionNonce, snapshotId)

    override fun click(snapshotId: String, nodeId: String): HostToolResult =
        active().click(snapshotId, nodeId)

    override fun clickText(text: String): HostToolResult = active().clickText(text)

    override fun scroll(direction: String, snapshotId: String?, nodeId: String?): HostToolResult =
        active().scroll(direction, snapshotId, nodeId)

    override fun back(): HostToolResult = active().back()

    override fun home(): HostToolResult = active().home()

    override fun setText(snapshotId: String, nodeId: String, value: String): HostToolResult =
        active().setText(snapshotId, nodeId, value)

    override fun waitFor(packageName: String?, timeoutMs: Long): HostToolResult =
        active().waitFor(packageName, timeoutMs)

    private fun active(): LabUiAutomationExecutor {
        // setText 的中文限制：shell 后端不可用时才降级，可用时仍由后端自行返回
        // host_shell_set_text_limited，上层可据此显式要求无障碍路径（后续可在工具层加 backend 参数）。
        return when {
            LabShellBackend.isAvailable() -> LabShellBackend
            else -> LabAccessibilityRegistry.a11yExecutor ?: LabReadOnlyBackend
        }
    }

    /** 供设置页/诊断显示当前后端与降级原因。 */
    fun status(): HostToolResult = HostToolResult.Ok(
        mapOf(
            "backend" to activeBackend(),
            "shell" to LabShellBackend.isAvailable().toString(),
            "a11y" to (LabAccessibilityRegistry.a11yExecutor != null).toString(),
        ),
    )
}
