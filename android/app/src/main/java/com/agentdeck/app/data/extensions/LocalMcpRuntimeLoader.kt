package com.agentdeck.app.data.extensions

import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.extensions.McpExtensionConfig
import org.json.JSONObject

internal fun interface LocalMcpRuntimeAdapter {
    fun apply(config: JSONObject, mcp: McpExtensionConfig)
}

/** Secure always returns a rejecting adapter; only the Lab source set contains an injector. */
internal object LocalMcpRuntimeLoader {
    val adapter: LocalMcpRuntimeAdapter by lazy {
        if (!BuildConfig.EXTENSION_LAB) {
            RejectingLocalMcpRuntime
        } else {
            runCatching {
                Class.forName("com.agentdeck.app.data.extensions.lab.LabLocalMcpRuntimeAdapter")
                    .getDeclaredConstructor()
                    .newInstance() as LocalMcpRuntimeAdapter
            }.getOrDefault(RejectingLocalMcpRuntime)
        }
    }
}

private object RejectingLocalMcpRuntime : LocalMcpRuntimeAdapter {
    override fun apply(config: JSONObject, mcp: McpExtensionConfig): Nothing =
        error("当前版本不包含本地 MCP 运行器")
}
