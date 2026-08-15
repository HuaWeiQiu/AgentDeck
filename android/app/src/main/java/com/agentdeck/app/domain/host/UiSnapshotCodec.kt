package com.agentdeck.app.domain.host

import org.json.JSONObject

object UiSnapshotCodec {
    fun fromJson(raw: String): UiSnapshot? {
        return runCatching {
            val root = JSONObject(raw)
            val nodes = root.optJSONArray("nodes") ?: return@runCatching null
            val parsed = buildList {
                for (index in 0 until nodes.length()) {
                    val value = nodes.getJSONObject(index)
                    val bounds = value.optJSONArray("bounds")
                    add(
                        UiSnapshotNode(
                            nodeId = value.getString("nodeId"),
                            parentId = value.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                            role = value.optString("role"),
                            text = value.optString("text"),
                            resourceId = value.optString("resourceId"),
                            bounds = if (bounds == null) emptyList() else List(bounds.length()) { bounds.optDouble(it).toFloat() },
                            states = jsonStrings(value.optJSONArray("states")),
                            actions = jsonStrings(value.optJSONArray("actions")),
                        ),
                    )
                }
            }
            UiSnapshot(
                schemaVersion = root.optInt("schemaVersion", 2),
                snapshotId = root.getString("snapshotId"),
                packageName = root.optString("packageName"),
                windowTitle = root.optString("windowTitle"),
                capturedAtEpochMs = root.optLong("capturedAtEpochMs"),
                truncated = root.optBoolean("truncated"),
                sensitive = root.optBoolean("sensitive"),
                nodes = parsed,
            )
        }.getOrNull()
    }

    private fun jsonStrings(array: org.json.JSONArray?): List<String> {
        array ?: return emptyList()
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }
}
