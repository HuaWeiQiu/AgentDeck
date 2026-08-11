package com.agentdeck.app.data.host.lab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabUiAutomationExecutor
import com.agentdeck.app.domain.host.NoOpLabUiExecutor

/**
 * Lab 屏幕代理：仅在用户于系统设置中开启本服务且 App 内 Lab UI 开关打开后使用。
 */
class LabAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        LabUiAutomationHolder.executor = ServiceExecutor
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            LabUiAutomationHolder.executor = NoOpLabUiExecutor
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: LabAccessibilityService? = null

        private object ServiceExecutor : LabUiAutomationExecutor {
            override fun snapshot(maxChars: Int): HostToolResult {
                val service = instance
                    ?: return HostToolResult.Denied(
                        "host_a11y_off",
                        "请在系统设置 → 无障碍 中开启 AgentDeck Lab 屏幕代理",
                    )
                val root = service.rootInActiveWindow
                    ?: return HostToolResult.Error("host_a11y_no_root", "无法读取当前窗口")
                val text = buildString {
                    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 24 || length >= maxChars) return
                        val label = sequenceOf(node.text, node.contentDescription)
                            .mapNotNull { it?.toString()?.trim() }
                            .firstOrNull { it.isNotEmpty() }
                        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                        if (label != null) {
                            append("  ".repeat(depth))
                            append(cls)
                            append(": ")
                            append(label.take(80))
                            append('\n')
                        }
                        for (i in 0 until node.childCount) {
                            walk(node.getChild(i), depth + 1)
                        }
                    }
                    walk(root, 0)
                }.take(maxChars)
                return HostToolResult.Ok(
                    mapOf("tree" to text, "chars" to text.length.toString()),
                    truncated = text.length >= maxChars,
                )
            }

            override fun clickText(text: String): HostToolResult {
                val service = instance
                    ?: return HostToolResult.Denied(
                        "host_a11y_off",
                        "请在系统设置 → 无障碍 中开启 AgentDeck Lab 屏幕代理",
                    )
                val root = service.rootInActiveWindow
                    ?: return HostToolResult.Error("host_a11y_no_root", "无法读取当前窗口")
                val needle = text.trim()
                if (needle.isEmpty() || needle.length > 80) {
                    return HostToolResult.Denied("host_bad_text", "点击文本无效")
                }
                val node = findByText(root, needle)
                    ?: return HostToolResult.Error("host_a11y_not_found", "未找到匹配文本")
                val target = if (node.isClickable) {
                    node
                } else {
                    generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                }
                if (target == null) {
                    return HostToolResult.Error("host_a11y_not_clickable", "节点不可点击")
                }
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return if (ok) {
                    HostToolResult.Ok(mapOf("clicked" to needle))
                } else {
                    HostToolResult.Error("host_a11y_click_failed", "点击失败")
                }
            }

            private fun findByText(node: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? {
                if (node == null) return null
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                if (t.contains(needle) || d.contains(needle)) return node
                for (i in 0 until node.childCount) {
                    findByText(node.getChild(i), needle)?.let { return it }
                }
                return null
            }
        }
    }
}
