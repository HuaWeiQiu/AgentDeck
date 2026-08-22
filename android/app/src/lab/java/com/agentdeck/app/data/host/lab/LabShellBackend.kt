package com.agentdeck.app.data.host.lab

import android.content.pm.PackageManager
import com.agentdeck.app.domain.host.AccessibilityTreeSanitizer
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabUiAutomationExecutor
import com.agentdeck.app.domain.host.RawUiNode
import com.agentdeck.app.domain.host.ShellTextInput
import com.agentdeck.app.domain.host.SensitiveUiClassifier
import com.agentdeck.app.domain.host.UiAutomatorDumpParser
import com.agentdeck.app.domain.host.UiAutomationLimits
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 三级后端之首选：经 Shizuku（shell UID）执行 uiautomator/input/dumpsys，
 * 不依赖无障碍服务。不可用（未安装/未授权）时所有调用返回 Denied，由路由器降级。
 *
 * snapshot 语义与无障碍后端对齐：同一 sanitizer、同一 nodeId（fingerprint 哈希）。
 */
internal object LabShellBackend : LabUiAutomationExecutor {

    private const val DUMP_TARGET = "/dev/tty"
    private const val COMMAND_TIMEOUT_MS = 8_000L

    /** 与无障碍后端的 lastSessionNonce 同语义，供 resolveNode 计算 nodeId。 */
    @Volatile
    var lastSessionNonce: String = ""
        private set

    fun isAvailable(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override fun currentApp(): HostToolResult {
        if (!isAvailable()) return unavailable()
        val focus = execOrNull("dumpsys window windows | grep -E mCurrentFocus")
            ?: return execFailed("currentApp")
        val pkg = firstPackageIn(focus)
        if (pkg.isBlank()) return HostToolResult.Error("host_shell_no_focus", "无法识别当前前台应用")
        return HostToolResult.Ok(
            mapOf(
                "package" to pkg,
                "windowTitle" to focus.trim().take(120),
                "backend" to BACKEND_NAME,
                "denied" to SensitiveUiClassifier.isDeniedPackage(pkg, selfPackage()).toString(),
            ),
        )
    }

    override fun snapshot(maxChars: Int, sessionNonce: String, snapshotId: String): HostToolResult {
        if (!isAvailable()) return unavailable()
        val xml = execOrNull("uiautomator dump $DUMP_TARGET") ?: return execFailed("snapshot")
        val (width, height) = windowSize()
        val raw = UiAutomatorDumpParser.parse(xml, width, height)
        if (raw.isEmpty()) return HostToolResult.Error("host_shell_dump_empty", "视图树为空或 dump 失败")
        val sanitized = AccessibilityTreeSanitizer.sanitize(
            rawNodes = raw,
            packageName = UiAutomatorDumpParser.packageNameOf(xml),
            windowTitle = "",
            snapshotId = snapshotId,
            sessionNonce = sessionNonce,
            capturedAtEpochMs = System.currentTimeMillis(),
            selfPackage = selfPackage(),
        )
        val json = AccessibilityTreeSanitizer.toJson(sanitized)
            .take(maxChars.coerceAtMost(UiAutomationLimits.MAX_JSON_CHARS))
        lastSessionNonce = sessionNonce
        return HostToolResult.Ok(
            mapOf(
                "schemaVersion" to "2",
                "snapshotId" to sanitized.snapshotId,
                "package" to sanitized.packageName,
                "sensitive" to sanitized.sensitive.toString(),
                "truncated" to (sanitized.truncated || json.length >= maxChars).toString(),
                "backend" to BACKEND_NAME,
                "snapshot" to json,
            ),
            truncated = sanitized.truncated || json.length >= maxChars,
        )
    }

    override fun click(snapshotId: String, nodeId: String): HostToolResult {
        val node = resolveNode(snapshotId, nodeId) ?: return staleSnapshot()
        return tapCenter(node)
    }

    override fun clickText(text: String): HostToolResult {
        val needle = text.trim()
        // input text/tap 参数走 shell，非 ASCII 文本匹配不可靠，与 setText 同口径限制。
        if (needle.isEmpty() || needle.length > 80 || ShellTextInput.safeArgumentOrNull(needle) == null) {
            return HostToolResult.Denied("host_bad_text", "点击文本无效")
        }
        val node = findNode { it.text.contains(needle) || it.contentDescription.contains(needle) }
            ?: return HostToolResult.Error("host_a11y_not_found", "未找到匹配文本")
        return tapCenter(node)
    }

    override fun scroll(direction: String, snapshotId: String?, nodeId: String?): HostToolResult {
        if (!isAvailable()) return unavailable()
        val node = if (!nodeId.isNullOrBlank() && !snapshotId.isNullOrBlank()) {
            resolveNode(snapshotId, nodeId)
        } else {
            null
        }
        val (_, screenHeight) = windowSize()
        val (x1, y1, x2, y2) = if (node != null && node.bounds.size == 4) {
            val cx = (node.bounds[0] + node.bounds[2]) / 2
            val cy = (node.bounds[1] + node.bounds[3]) / 2
            val span = ((node.bounds[3] - node.bounds[1]) / 2).coerceIn(120, 600)
            listOf(cx, if (direction.equals("up", true)) cy - span else cy + span,
                cx, if (direction.equals("up", true)) cy + span else cy - span)
        } else {
            val cx = screenWidth() / 2
            listOf(cx, (screenHeight * 0.7).toInt(), cx, (screenHeight * 0.3).toInt())
                .let { if (direction.equals("up", true)) it else listOf(it[0], it[3], it[2], it[1]) }
        }
        return runInput(arrayOf("input", "swipe", "$x1", "$y1", "$x2", "$y2", "300"), "scroll")
    }

    override fun back(): HostToolResult = keyevent(4)

    override fun home(): HostToolResult = keyevent(3)

    override fun setText(snapshotId: String, nodeId: String, value: String): HostToolResult {
        val node = resolveNode(snapshotId, nodeId) ?: return staleSnapshot()
        if (node.password) {
            return HostToolResult.Denied("SENSITIVE_SCREEN", "不能自动填写密码或敏感内容")
        }
        val arg = ShellTextInput.safeArgumentOrNull(value)
            ?: return HostToolResult.Error(
                "host_shell_set_text_limited",
                "shell 后端仅支持 ASCII 文本输入；中文等场景请切回无障碍后端",
            )
        val (cx, cy) = center(node)
        // 先聚焦输入框再写入；input text 的 %s 即空格。
        runInput(arrayOf("input", "tap", "$cx", "$cy"), "focus")
        Thread.sleep(150)
        return runInput(arrayOf("input", "text", arg), "filled")
    }

    override fun waitFor(packageName: String?, timeoutMs: Long): HostToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 15_000L)
        while (System.currentTimeMillis() < deadline) {
            val focus = execOrNull("dumpsys window windows | grep -E mCurrentFocus").orEmpty()
            val pkg = firstPackageIn(focus)
            if (packageName.isNullOrBlank() || pkg == packageName) {
                return HostToolResult.Ok(mapOf("package" to pkg, "ready" to "true"))
            }
            Thread.sleep(150)
        }
        return HostToolResult.Error("host_a11y_timeout", "等待界面超时")
    }

    // ---- 内部工具 ----

    private const val BACKEND_NAME = "shell"

    /** 重新 dump 并按 nodeId 定位节点。nodeId 由 sanitizer 的 fingerprint 哈希生成。 */
    private fun resolveNode(snapshotId: String, nodeId: String): RawUiNode? {
        val xml = execOrNull("uiautomator dump $DUMP_TARGET") ?: return null
        val (width, height) = windowSize()
        return UiAutomatorDumpParser.parse(xml, width, height).firstOrNull {
            AccessibilityTreeSanitizer.nodeId(lastSessionNonce, snapshotId, it.fingerprint) == nodeId
        }
    }

    private fun findNode(predicate: (RawUiNode) -> Boolean): RawUiNode? {
        val xml = execOrNull("uiautomator dump $DUMP_TARGET") ?: return null
        val (width, height) = windowSize()
        return UiAutomatorDumpParser.parse(xml, width, height).firstOrNull(predicate)
    }

    private fun tapCenter(node: RawUiNode): HostToolResult {
        val (x, y) = center(node)
        return runInput(arrayOf("input", "tap", "$x", "$y"), "clicked")
    }

    private fun center(node: RawUiNode): Pair<Int, Int> =
        (node.bounds.getOrElse(0) { 0 } + node.bounds.getOrElse(2) { 0 }) / 2 to
            (node.bounds.getOrElse(1) { 0 } + node.bounds.getOrElse(3) { 0 }) / 2

    private fun keyevent(code: Int): HostToolResult {
        if (!isAvailable()) return unavailable()
        val action = if (code == 4) "back" else "home"
        return runInput(arrayOf("input", "keyevent", code.toString()), action)
    }

    private fun runInput(args: Array<String>, resultKey: String): HostToolResult {
        if (!execArgsOk(args)) return execFailed(resultKey)
        return HostToolResult.Ok(mapOf(resultKey.replaceFirstChar { it.lowercase() } to "true"))
    }

    private fun execArgsOk(args: Array<String>): Boolean {
        // input tap/swipe/text 参数不含 shell 元字符（数字与转义后文本），直接作为单命令执行。
        return runCatching {
            val json = LabShizukuConnection.exec(args.joinToString(" ")) ?: return@runCatching false
            JSONObject(json).optInt("exit", 1) == 0 && !JSONObject(json).optBoolean("timeout", false)
        }.getOrDefault(false)
    }

    private fun execOrNull(command: String): String? {
        val json = runCatching { LabShizukuConnection.exec(command) }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optBoolean("timeout", false)) null else obj.optString("output").ifBlank { null }
        }.getOrNull()
    }

    private fun windowSize(): Pair<Int, Int> =
        UiAutomatorDumpParser.windowSizeFromWmSize(execOrNull("wm size").orEmpty())

    private fun screenWidth(): Int = windowSize().first

    /** 从 mCurrentFocus 输出提取第一个像包名的 token。 */
    private fun firstPackageIn(output: String): String =
        Regex("""\b([a-z][a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)""").findAll(output)
            .firstOrNull()?.groupValues?.get(1).orEmpty()

    private fun selfPackage(): String = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as android.app.Application
        app.packageName
    }.getOrDefault("com.agentdeck.app")

    private fun unavailable(): HostToolResult.Denied = HostToolResult.Denied(
        "host_backend_unavailable",
        "Shizuku 未运行或未授权；请在开发者选项完成无线调试配对，或改用无障碍后端",
    )

    private fun execFailed(action: String): HostToolResult =
        HostToolResult.Error("host_shell_exec_failed", "$action 执行失败或超时")

    private fun staleSnapshot(): HostToolResult.Denied =
        HostToolResult.Denied("STALE_SNAPSHOT", "界面已变化，请重新观察")
}
