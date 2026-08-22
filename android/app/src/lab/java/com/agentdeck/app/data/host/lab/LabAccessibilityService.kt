package com.agentdeck.app.data.host.lab

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentdeck.app.domain.host.AccessibilityTreeSanitizer
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabUiAutomationExecutor
import com.agentdeck.app.domain.host.RawUiNode
import com.agentdeck.app.domain.host.SensitiveUiClassifier
import com.agentdeck.app.domain.host.UiAutomationLimits

class LabAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        LabAccessibilityRegistry.a11yExecutor = ServiceExecutor
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            LabAccessibilityRegistry.a11yExecutor = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: LabAccessibilityService? = null
        @Volatile
        private var lastSessionNonce: String = ""

        private object ServiceExecutor : LabUiAutomationExecutor {
            override fun currentApp(): HostToolResult {
                val root = activeRoot() ?: return noRoot()
                val pkg = root.packageName?.toString().orEmpty()
                val title = root.text?.toString().orEmpty()
                return HostToolResult.Ok(
                    mapOf(
                        "package" to pkg,
                        "windowTitle" to title,
                        "denied" to SensitiveUiClassifier.isDeniedPackage(
                            pkg,
                            instance?.packageName.orEmpty(),
                        ).toString(),
                    ),
                )
            }

            override fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String): HostToolResult {
                val service = instance ?: return a11yOff()
                lastSessionNonce = sessionNonce
                val root = service.rootInActiveWindow ?: return noRoot()
                val pkg = root.packageName?.toString().orEmpty()
                val raw = collect(root, service.packageName.orEmpty())
                val snapshot = AccessibilityTreeSanitizer.sanitize(
                    rawNodes = raw,
                    packageName = pkg,
                    windowTitle = root.text?.toString().orEmpty(),
                    snapshotId = snapshotId,
                    sessionNonce = sessionNonce,
                    capturedAtEpochMs = System.currentTimeMillis(),
                    selfPackage = service.packageName.orEmpty(),
                )
                val json = AccessibilityTreeSanitizer.toJson(snapshot).take(maxChars.coerceAtMost(UiAutomationLimits.MAX_JSON_CHARS))
                return HostToolResult.Ok(
                    mapOf(
                        "schemaVersion" to "2",
                        "snapshotId" to snapshot.snapshotId,
                        "package" to snapshot.packageName,
                        "sensitive" to snapshot.sensitive.toString(),
                        "truncated" to (snapshot.truncated || json.length >= maxChars).toString(),
                        "snapshot" to json,
                    ),
                    truncated = snapshot.truncated || json.length >= maxChars,
                )
            }

            override fun click(snapshotId: String, nodeId: String): HostToolResult {
                val node = findById(nodeId, snapshotId) ?: return stale()
                val target = if (node.isClickable) node else generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                if (target == null) return HostToolResult.Error("host_a11y_not_clickable", "节点不可点击")
                return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    HostToolResult.Ok(mapOf("clicked" to nodeId))
                } else {
                    HostToolResult.Error("host_a11y_click_failed", "点击失败")
                }
            }

            override fun clickText(text: String): HostToolResult {
                val root = activeRoot() ?: return noRoot()
                val needle = text.trim()
                if (needle.isEmpty() || needle.length > 80) {
                    return HostToolResult.Denied("host_bad_text", "点击文本无效")
                }
                val node = findByText(root, needle)
                    ?: return HostToolResult.Error("host_a11y_not_found", "未找到匹配文本")
                val target = if (node.isClickable) node else generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                if (target == null) return HostToolResult.Error("host_a11y_not_clickable", "节点不可点击")
                return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    HostToolResult.Ok(mapOf("clicked" to needle))
                } else {
                    HostToolResult.Error("host_a11y_click_failed", "点击失败")
                }
            }

            override fun scroll(direction: String, snapshotId: String?, nodeId: String?): HostToolResult {
                val root = activeRoot() ?: return noRoot()
                val target = if (!nodeId.isNullOrBlank() && !snapshotId.isNullOrBlank()) {
                    findById(nodeId, snapshotId)
                } else {
                    findScrollable(root)
                } ?: return HostToolResult.Error("host_a11y_not_found", "没有可滚动区域")
                val action = if (direction.equals("up", ignoreCase = true)) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                return if (target.performAction(action)) {
                    HostToolResult.Ok(mapOf("scrolled" to direction))
                } else {
                    HostToolResult.Error("host_a11y_scroll_failed", "滚动失败")
                }
            }

            override fun back(): HostToolResult {
                val service = instance ?: return a11yOff()
                return if (service.performGlobalAction(GLOBAL_ACTION_BACK)) {
                    HostToolResult.Ok(mapOf("action" to "back"))
                } else {
                    HostToolResult.Error("host_a11y_back_failed", "返回失败")
                }
            }

            override fun home(): HostToolResult {
                val service = instance ?: return a11yOff()
                return if (service.performGlobalAction(GLOBAL_ACTION_HOME)) {
                    HostToolResult.Ok(mapOf("action" to "home"))
                } else {
                    HostToolResult.Error("host_a11y_home_failed", "回到桌面失败")
                }
            }

            override fun setText(snapshotId: String, nodeId: String, value: String): HostToolResult {
                val node = findById(nodeId, snapshotId) ?: return stale()
                if (node.isPassword) {
                    return HostToolResult.Denied("SENSITIVE_SCREEN", "不能自动填写密码或敏感内容")
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    HostToolResult.Ok(mapOf("filled" to "true"))
                } else {
                    HostToolResult.Error("host_a11y_set_text_failed", "填写失败")
                }
            }

            override fun waitFor(packageName: String?, timeoutMs: Long): HostToolResult {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val root = instance?.rootInActiveWindow
                    val pkg = root?.packageName?.toString().orEmpty()
                    if (packageName.isNullOrBlank() || pkg == packageName) {
                        return HostToolResult.Ok(mapOf("package" to pkg, "ready" to "true"))
                    }
                    Thread.sleep(80)
                }
                return HostToolResult.Error("host_a11y_timeout", "等待界面超时")
            }

            private fun collect(root: AccessibilityNodeInfo, selfPackage: String): List<RawUiNode> {
                val window = Rect()
                root.getBoundsInScreen(window)
                val width = (window.width()).coerceAtLeast(1)
                val height = (window.height()).coerceAtLeast(1)
                val out = ArrayList<RawUiNode>()
                fun walk(node: AccessibilityNodeInfo?, depth: Int, parent: String?) {
                    if (node == null || depth > UiAutomationLimits.MAX_DEPTH || out.size >= UiAutomationLimits.MAX_NODES) return
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val fingerprint = listOf(
                        node.className,
                        node.viewIdResourceName,
                        node.text,
                        bounds.flattenToString(),
                    ).joinToString("|")
                    out += RawUiNode(
                        fingerprint = fingerprint,
                        parentFingerprint = parent,
                        packageName = node.packageName?.toString().orEmpty().ifBlank { root.packageName?.toString().orEmpty() },
                        className = node.className?.toString().orEmpty(),
                        text = node.text?.toString().orEmpty(),
                        contentDescription = node.contentDescription?.toString().orEmpty(),
                        resourceId = node.viewIdResourceName.orEmpty(),
                        password = node.isPassword,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        enabled = node.isEnabled,
                        focused = node.isFocused,
                        bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        windowWidth = width,
                        windowHeight = height,
                    )
                    for (index in 0 until node.childCount) {
                        walk(node.getChild(index), depth + 1, fingerprint)
                    }
                }
                walk(root, 0, null)
                return out
            }

            private fun findById(nodeId: String, snapshotId: String): AccessibilityNodeInfo? {
                val service = instance ?: return null
                val root = service.rootInActiveWindow ?: return null
                val raw = collect(root, service.packageName.orEmpty())
                val match = raw.firstOrNull {
                    AccessibilityTreeSanitizer.nodeId(lastSessionNonce, snapshotId, it.fingerprint) == nodeId
                } ?: return null
                return findRaw(root, match.fingerprint)
            }

            private fun findRaw(node: AccessibilityNodeInfo?, fingerprint: String): AccessibilityNodeInfo? {
                if (node == null) return null
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val current = listOf(node.className, node.viewIdResourceName, node.text, bounds.flattenToString()).joinToString("|")
                if (current == fingerprint) return node
                for (index in 0 until node.childCount) {
                    findRaw(node.getChild(index), fingerprint)?.let { return it }
                }
                return null
            }

            private fun findByText(node: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? {
                if (node == null) return null
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                if (t.contains(needle) || d.contains(needle)) return node
                for (index in 0 until node.childCount) {
                    findByText(node.getChild(index), needle)?.let { return it }
                }
                return null
            }

            private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isScrollable) return node
                for (index in 0 until node.childCount) {
                    findScrollable(node.getChild(index))?.let { return it }
                }
                return null
            }

            private fun activeRoot(): AccessibilityNodeInfo? = instance?.rootInActiveWindow
            private fun a11yOff() = HostToolResult.Denied("host_a11y_off", "请在系统设置 → 无障碍 中开启 AgentDeck Lab 屏幕代理")
            private fun noRoot() = HostToolResult.Error("host_a11y_no_root", "无法读取当前窗口")
            private fun stale() = HostToolResult.Denied("STALE_SNAPSHOT", "界面已变化，请重新观察")
        }
    }
}
