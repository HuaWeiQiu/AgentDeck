package com.agentdeck.app.domain.host

import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 解析 `uiautomator dump /dev/tty` 输出的视图层级 XML，映射为 [RawUiNode] 列表。
 * fingerprint 语义与无障碍后端的 collect() 保持一致：
 * className|resourceId|text|bounds，供 AccessibilityTreeSanitizer.nodeId 复用。
 */
object UiAutomatorDumpParser {

    private const val MAX_DEPTH = UiAutomationLimits.MAX_DEPTH
    private const val MAX_NODES = UiAutomationLimits.MAX_NODES

    fun parse(xml: String, windowWidth: Int, windowHeight: Int): List<RawUiNode> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<RawUiNode>()
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val fingerprintStack = ArrayDeque<String>()
        var depth = 0
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && out.size < MAX_NODES) {
            when (eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "node") {
                    depth += 1
                    if (depth <= MAX_DEPTH) {
                        val raw = nodeOf(parser, windowWidth, windowHeight, fingerprintStack.lastOrNull())
                        out += raw
                        fingerprintStack.addLast(raw.fingerprint)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "node") {
                    if (depth > 0) {
                        depth -= 1
                        if (depth < fingerprintStack.size) fingerprintStack.removeLast()
                    }
                }
            }
            eventType = parser.next()
        }
        return out
    }

    /** 从 dump 根节点或节点属性提取包名；无属性时返回空串。 */
    fun packageNameOf(xml: String): String {
        val match = Regex("""package="([^"]+)"""").find(xml) ?: return ""
        return match.groupValues[1]
    }

    /** 解析 `wm size` 输出（Physical size: WxH / Override size: WxH），失败返回 1x1。 */
    fun windowSizeFromWmSize(output: String): Pair<Int, Int> {
        val match = Regex("""(\d+)x(\d+)""").findAll(output).lastOrNull() ?: return 1 to 1
        val w = match.groupValues[1].toIntOrNull() ?: return 1 to 1
        val h = match.groupValues[2].toIntOrNull() ?: return 1 to 1
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun nodeOf(
        parser: XmlPullParser,
        windowWidth: Int,
        windowHeight: Int,
        parentFingerprint: String?,
    ): RawUiNode {
        val className = parser.getAttributeValue(null, "class").orEmpty()
        val resourceId = parser.getAttributeValue(null, "resource-id").orEmpty()
        val text = parser.getAttributeValue(null, "text").orEmpty()
        val contentDescription = parser.getAttributeValue(null, "content-desc").orEmpty()
        val bounds = parser.getAttributeValue(null, "bounds").orEmpty()
        val rect = parseBounds(bounds)
        val width = windowWidth.coerceAtLeast(1)
        val height = windowHeight.coerceAtLeast(1)
        val fingerprint = listOf(className, resourceId, text, bounds).joinToString("|")
        return RawUiNode(
            fingerprint = fingerprint,
            parentFingerprint = parentFingerprint,
            packageName = parser.getAttributeValue(null, "package").orEmpty(),
            className = className,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            password = parser.getAttributeValue(null, "password") == "true",
            clickable = parser.getAttributeValue(null, "clickable") == "true",
            // uiautomator dump 没有 editable 属性；与 AccessibilityTreeSanitizer.roleOf 同口径，按类名启发。
            editable = className.lowercase().contains("edit"),
            scrollable = parser.getAttributeValue(null, "scrollable") == "true",
            enabled = parser.getAttributeValue(null, "enabled") != "false",
            focused = parser.getAttributeValue(null, "focused") == "true",
            bounds = intArrayOf(rect[0], rect[1], rect[2], rect[3]),
            windowWidth = width,
            windowHeight = height,
        )
    }

    /** bounds 形如 "[0,120][540,240]"；解析失败返回全零矩形。 */
    fun parseBounds(value: String): IntArray {
        val numbers = Regex("""-?\d+""").findAll(value).toList()
        if (numbers.size < 4) return intArrayOf(0, 0, 0, 0)
        return intArrayOf(
            numbers[0].value.toIntOrNull() ?: 0,
            numbers[1].value.toIntOrNull() ?: 0,
            numbers[2].value.toIntOrNull() ?: 0,
            numbers[3].value.toIntOrNull() ?: 0,
        )
    }
}
