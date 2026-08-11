package com.agentdeck.app.data.extensions

import org.json.JSONObject

internal object ExtensionConfigComposer {
    fun merge(
        base: JSONObject,
        overlay: JSONObject,
        managedOnly: Boolean,
        inheritedServerIds: Set<String>,
    ): JSONObject {
        val merged = JSONObject(base.toString())
        val managedServers = overlay.optJSONObject("mcp_servers") ?: JSONObject()
        if (!managedOnly) {
            val existing = merged.optJSONObject("mcp_servers") ?: JSONObject()
            managedServers.keys().forEach { key ->
                existing.put(key, JSONObject(managedServers.getJSONObject(key).toString()))
            }
            return merged.put("mcp_servers", existing)
        }

        val secureServers = JSONObject()
        val conflicts = inheritedServerIds.filter(managedServers::has)
        require(conflicts.isEmpty()) {
            "受管 MCP 名称与 Codex 用户或项目配置冲突，请移除同名原始 MCP"
        }
        inheritedServerIds.forEach { id ->
            if (!managedServers.has(id)) secureServers.put(id, JSONObject().put("enabled", false))
        }
        managedServers.keys().forEach { key ->
            secureServers.put(key, JSONObject(managedServers.getJSONObject(key).toString()))
        }
        return merged.put("mcp_servers", secureServers)
    }
}
