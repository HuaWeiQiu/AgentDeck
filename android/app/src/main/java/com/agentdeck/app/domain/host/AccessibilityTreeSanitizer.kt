package com.agentdeck.app.domain.host

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object AccessibilityTreeSanitizer {
    fun sanitize(
        rawNodes: List<RawUiNode>,
        packageName: String,
        windowTitle: String,
        snapshotId: String,
        sessionNonce: String,
        capturedAtEpochMs: Long,
        selfPackage: String,
    ): UiSnapshot {
        val kept = rawNodes.filter(::keepNode).take(UiAutomationLimits.MAX_NODES)
        val sensitive = SensitiveUiClassifier.isSensitiveScreen(rawNodes, packageName, selfPackage)
        val nodes = kept.map { raw ->
            UiSnapshotNode(
                nodeId = nodeId(sessionNonce, snapshotId, raw.fingerprint),
                parentId = raw.parentFingerprint?.let { nodeId(sessionNonce, snapshotId, it) },
                role = roleOf(raw),
                text = (raw.text.ifBlank { raw.contentDescription }).take(UiAutomationLimits.MAX_FIELD_CHARS),
                resourceId = raw.resourceId.substringAfterLast('/').take(UiAutomationLimits.MAX_FIELD_CHARS),
                bounds = normalizedBounds(raw),
                states = statesOf(raw),
                actions = actionsOf(raw),
            )
        }
        return UiSnapshot(
            snapshotId = snapshotId,
            packageName = packageName,
            windowTitle = windowTitle.take(UiAutomationLimits.MAX_FIELD_CHARS),
            capturedAtEpochMs = capturedAtEpochMs,
            truncated = rawNodes.size > kept.size || sensitive,
            sensitive = sensitive,
            nodes = if (sensitive) emptyList() else nodes,
        )
    }

    fun toJson(snapshot: UiSnapshot): String {
        val root = JSONObject()
            .put("schemaVersion", snapshot.schemaVersion)
            .put("snapshotId", snapshot.snapshotId)
            .put("packageName", snapshot.packageName)
            .put("windowTitle", snapshot.windowTitle)
            .put("capturedAtEpochMs", snapshot.capturedAtEpochMs)
            .put("truncated", snapshot.truncated)
            .put("sensitive", snapshot.sensitive)
        val nodes = JSONArray()
        snapshot.nodes.forEach { node ->
            nodes.put(
                JSONObject()
                    .put("nodeId", node.nodeId)
                    .put("parentId", node.parentId)
                    .put("role", node.role)
                    .put("text", node.text)
                    .put("resourceId", node.resourceId)
                    .put("bounds", JSONArray(node.bounds))
                    .put("states", JSONArray(node.states))
                    .put("actions", JSONArray(node.actions)),
            )
        }
        root.put("nodes", nodes)
        return root.toString()
    }

    fun nodeId(sessionNonce: String, snapshotId: String, fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((sessionNonce + ":" + snapshotId + ":" + fingerprint).toByteArray())
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun keepNode(node: RawUiNode): Boolean {
        return node.text.isNotBlank() ||
            node.contentDescription.isNotBlank() ||
            node.resourceId.isNotBlank() ||
            node.clickable ||
            node.editable ||
            node.scrollable
    }

    private fun roleOf(node: RawUiNode): String {
        val cls = node.className.lowercase()
        return when {
            node.editable || cls.contains("edit") -> "edit"
            cls.contains("button") -> "button"
            cls.contains("switch") || cls.contains("check") -> "toggle"
            cls.contains("recycle") || cls.contains("list") || cls.contains("scroll") -> "list"
            node.clickable -> "clickable"
            else -> "text"
        }
    }

    private fun statesOf(node: RawUiNode): List<String> = buildList {
        if (node.enabled) add("enabled") else add("disabled")
        if (node.clickable) add("clickable")
        if (node.editable) add("editable")
        if (node.scrollable) add("scrollable")
        if (node.focused) add("focused")
        if (node.password) add("password")
    }

    private fun actionsOf(node: RawUiNode): List<String> = buildList {
        if (node.clickable) add("click")
        if (node.editable) add("set_text")
        if (node.scrollable) add("scroll")
    }

    private fun normalizedBounds(node: RawUiNode): List<Float> {
        val width = node.windowWidth.coerceAtLeast(1).toFloat()
        val height = node.windowHeight.coerceAtLeast(1).toFloat()
        val left = node.bounds.getOrElse(0) { 0 }.toFloat() / width
        val top = node.bounds.getOrElse(1) { 0 }.toFloat() / height
        val right = node.bounds.getOrElse(2) { 0 }.toFloat() / width
        val bottom = node.bounds.getOrElse(3) { 0 }.toFloat() / height
        return listOf(left, top, right, bottom).map { it.coerceIn(0f, 1f) }
    }
}
