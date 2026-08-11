package com.agentdeck.app.data.extensions.lab

import androidx.annotation.Keep
import com.agentdeck.app.data.extensions.LocalMcpRuntimeAdapter
import com.agentdeck.app.domain.extensions.McpExtensionConfig
import org.json.JSONArray
import org.json.JSONObject

@Keep
class LabLocalMcpRuntimeAdapter : LocalMcpRuntimeAdapter {
    override fun apply(config: JSONObject, mcp: McpExtensionConfig) {
        val command = requireNotNull(mcp.command) { "本地 MCP 命令不存在" }
        config.put("command", command)
        config.put("args", JSONArray(mcp.args))
    }
}
