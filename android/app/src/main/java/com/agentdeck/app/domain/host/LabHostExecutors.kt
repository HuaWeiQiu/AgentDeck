package com.agentdeck.app.domain.host

/**
 * Lab L2–L4 执行器接口。Secure 通道使用 [NoOp] 实现（永远 Denied）。
 */
interface LabIntentExecutor {
    fun openUrl(url: String): HostToolResult
    fun shareText(text: String): HostToolResult
}

interface LabUiAutomationExecutor {
    fun currentApp(): HostToolResult
    fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String): HostToolResult
    fun click(snapshotId: String, nodeId: String): HostToolResult
    fun clickText(text: String): HostToolResult
    fun scroll(direction: String, snapshotId: String?, nodeId: String?): HostToolResult
    fun back(): HostToolResult
    fun home(): HostToolResult
    fun setText(snapshotId: String, nodeId: String, value: String): HostToolResult
    fun waitFor(packageName: String?, timeoutMs: Long): HostToolResult
}

interface LabPrivilegedExecutor {
    fun shell(command: String): HostToolResult
    fun status(): HostToolResult
}

object NoOpLabIntentExecutor : LabIntentExecutor {
    override fun openUrl(url: String) = notLab()
    override fun shareText(text: String) = notLab()
    private fun notLab() = HostToolResult.Denied(
        "host_channel_cap",
        "当前为安全版，不包含 Lab Intent 能力",
    )
}

object NoOpLabUiExecutor : LabUiAutomationExecutor {
    override fun currentApp() = notLab()
    override fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String) = notLab()
    override fun click(snapshotId: String, nodeId: String) = notLab()
    override fun clickText(text: String) = notLab()
    override fun scroll(direction: String, snapshotId: String?, nodeId: String?) = notLab()
    override fun back() = notLab()
    override fun home() = notLab()
    override fun setText(snapshotId: String, nodeId: String, value: String) = notLab()
    override fun waitFor(packageName: String?, timeoutMs: Long) = notLab()
    private fun notLab() = HostToolResult.Denied(
        "host_channel_cap",
        "当前为安全版，不包含屏幕代理",
    )
}

object NoOpLabPrivilegedExecutor : LabPrivilegedExecutor {
    override fun shell(command: String) = notLab()
    override fun status() = notLab()
    private fun notLab() = HostToolResult.Denied(
        "host_channel_cap",
        "当前为安全版，不包含特权壳",
    )
}
