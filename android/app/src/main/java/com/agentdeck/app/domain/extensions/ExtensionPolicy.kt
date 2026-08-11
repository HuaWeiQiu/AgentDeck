package com.agentdeck.app.domain.extensions

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ExtensionPolicy(
    private val maxLevel: Int,
) {
    init {
        require(maxLevel in ExtensionLevel.SKILL.value..ExtensionLevel.HOST_CONTROL.value)
    }

    fun requireAllowed(level: ExtensionLevel) {
        require(level.value <= maxLevel) {
            "当前版本不允许 ${level.displayName()} 扩展"
        }
    }

    fun validateRemoteUrl(value: String): HttpUrl {
        requireAllowed(ExtensionLevel.REMOTE_READ)
        require(value.length <= MAX_URL_LENGTH && value.none(Char::isISOControl)) {
            "MCP 地址无效"
        }
        val url = value.toHttpUrlOrNull() ?: error("MCP 地址格式无效")
        require(url.scheme == "https") { "安全版只允许 HTTPS MCP 服务" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "MCP 地址不能包含账号或密码" }
        require(url.fragment == null) { "MCP 地址不能包含片段" }
        require(url.query == null) { "MCP 地址不能包含查询参数；请使用受保护的 Bearer Token" }
        require(url.host !in BLOCKED_HOST_NAMES && !isPrivateLiteral(url.host)) {
            "安全版不能连接本机或私有网络 MCP 地址"
        }
        return url
    }

    fun validateLocalCommand(command: String, args: List<String>) {
        requireAllowed(ExtensionLevel.LOCAL_PROCESS)
        require(LOCAL_COMMAND_PATTERN.matches(command)) { "本地 MCP 命令必须是受限的绝对路径" }
        require(args.size <= MAX_ARGS && args.all { it.length <= MAX_ARG_LENGTH && it.none(Char::isISOControl) }) {
            "本地 MCP 参数无效"
        }
    }

    fun levelFor(kind: ExtensionKind, tools: List<ExtensionTool>): ExtensionLevel = when (kind) {
        ExtensionKind.SKILL -> ExtensionLevel.SKILL
        ExtensionKind.LOCAL_MCP -> ExtensionLevel.LOCAL_PROCESS
        ExtensionKind.REMOTE_MCP -> if (tools.any { it.enabled && it.access != ExtensionToolAccess.READ }) {
            ExtensionLevel.REMOTE_WRITE
        } else {
            ExtensionLevel.REMOTE_READ
        }
    }.also(::requireAllowed)

    fun normalizeTools(tools: List<ExtensionTool>): List<ExtensionTool> {
        require(tools.size <= MAX_TOOLS) { "MCP 工具数量超过 $MAX_TOOLS 个" }
        val names = hashSetOf<String>()
        return tools.map { tool ->
            val name = tool.name.trim()
            val title = tool.title.trim().ifBlank { name }
            val description = tool.description.trim()
            require(TOOL_NAME_PATTERN.matches(name) && names.add(name)) {
                "MCP 工具名称无效或重复"
            }
            require(title.length <= MAX_TOOL_TITLE_LENGTH && title.none(Char::isISOControl)) {
                "MCP 工具标题无效或过长"
            }
            require(
                description.length <= MAX_TOOL_DESCRIPTION_LENGTH &&
                    description.none { it.isISOControl() && it !in "\n\r\t" },
            ) { "MCP 工具说明无效或过长" }
            tool.copy(name = name, title = title, description = description)
        }
    }

    private fun ExtensionLevel.displayName(): String = when (this) {
        ExtensionLevel.SKILL -> "Skill"
        ExtensionLevel.REMOTE_READ -> "远程只读 MCP"
        ExtensionLevel.REMOTE_WRITE -> "远程写入 MCP"
        ExtensionLevel.LOCAL_PROCESS -> "本地进程 MCP"
        ExtensionLevel.HOST_CONTROL -> "宿主控制"
    }

    companion object {
        private const val MAX_URL_LENGTH = 2_048
        private const val MAX_ARGS = 64
        private const val MAX_ARG_LENGTH = 1_024
        private const val MAX_TOOLS = 256
        private const val MAX_TOOL_TITLE_LENGTH = 240
        private const val MAX_TOOL_DESCRIPTION_LENGTH = 2_000
        private val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_.:/-]{1,160}")
        private val LOCAL_COMMAND_PATTERN = Regex("/(usr/(local/)?bin|opt/[A-Za-z0-9._/-]+)/[A-Za-z0-9._+-]+")
        private val BLOCKED_HOST_NAMES = setOf(
            "localhost",
            "localhost.localdomain",
            "metadata.google.internal",
        )

        internal fun isPrivateLiteral(host: String): Boolean {
            val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
            if (normalized == "::1" || normalized.startsWith("fe80:") ||
                normalized.startsWith("fc") || normalized.startsWith("fd")
            ) return true
            val parts = normalized.split('.').mapNotNull(String::toIntOrNull)
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 0 || parts[0] == 10 || parts[0] == 127 ||
                parts[0] == 100 && parts[1] in 64..127 ||
                parts[0] == 169 && parts[1] == 254 ||
                parts[0] == 172 && parts[1] in 16..31 ||
                parts[0] == 192 && parts[1] == 0 && (parts[2] == 0 || parts[2] == 2) ||
                parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 198 && parts[1] in 18..19 ||
                parts[0] == 198 && parts[1] == 51 && parts[2] == 100 ||
                parts[0] == 203 && parts[1] == 0 && parts[2] == 113 ||
                parts[0] >= 224
        }
    }
}
