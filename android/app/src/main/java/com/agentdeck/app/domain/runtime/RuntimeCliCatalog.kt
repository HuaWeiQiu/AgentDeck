package com.agentdeck.app.domain.runtime

/**
 * How the user interacts with a CLI product surface inside AgentDeck.
 * Not the same as install state: Codex chat is native; dsh is a Web UI; pi is terminal/RPC.
 */
enum class RuntimeCliSurface {
    NATIVE_CHAT,
    WEB_UI,
    TERMINAL_AGENT,
}

data class RuntimeCliKind(
    val id: String,
    val displayName: String,
    val recipeId: String,
    val available: Boolean,
    val comingSoon: Boolean,
    val surface: RuntimeCliSurface,
    val productBlurb: String,
    val versions: List<RuntimeCliVersion>,
)

data class RuntimeCliVersion(
    val id: String,
    val label: String,
    val selected: Boolean,
    val downloadBytes: Long,
    val notes: String,
)

data class RuntimeCliStatus(
    val kind: RuntimeCliKind,
    val installed: Boolean,
    val installedVersionLabel: String?,
    val selectedVersion: RuntimeCliVersion,
    val usedBytes: Long,
    val canDelete: Boolean,
    val canOpen: Boolean = false,
    val canPrepare: Boolean = false,
)

object RuntimeCliCatalog {
    const val CODEX = "codex"
    const val DEEPSEEK_HARNESS = "deepseek-harness"
    const val PI = "pi"
    const val CLAUDE_CODE = "claude-code"

    fun kinds(
        codexVersion: String,
        codexDownloadBytes: Long,
        dshDownloadBytes: Long,
        dshVersionLabel: String,
        piDownloadBytes: Long = 0L,
        piVersionLabel: String = "pi",
    ): List<RuntimeCliKind> = listOf(
        RuntimeCliKind(
            id = CODEX,
            displayName = "Codex",
            recipeId = "recipe_codex",
            available = true,
            comingSoon = false,
            surface = RuntimeCliSurface.NATIVE_CHAT,
            productBlurb = "原生聊天：对话列表里的 Codex 会话，走 app-server",
            versions = listOf(
                RuntimeCliVersion(
                    id = "codex-" + codexVersion,
                    label = "Codex " + codexVersion,
                    selected = true,
                    downloadBytes = codexDownloadBytes,
                    notes = "当前已校验版本，聊天协议按此版本对接",
                ),
            ),
        ),
        RuntimeCliKind(
            id = DEEPSEEK_HARNESS,
            displayName = "DeepSeek Harness (dsh)",
            recipeId = "recipe_deepseek_harness",
            available = true,
            comingSoon = false,
            surface = RuntimeCliSurface.WEB_UI,
            productBlurb = "Web 版助手：本机 127.0.0.1 网页；需先有 Codex Linux；密钥在 dsh 内配置",
            versions = listOf(
                RuntimeCliVersion(
                    id = "dsh-" + dshVersionLabel,
                    label = dshVersionLabel,
                    selected = true,
                    downloadBytes = dshDownloadBytes,
                    notes = "官方 Node 发行版 + npm 钉死 @deepseek-ai/dsh；不进 Codex 聊天时间线",
                ),
            ),
        ),
        RuntimeCliKind(
            id = PI,
            displayName = "pi",
            recipeId = "recipe_pi",
            available = true,
            comingSoon = false,
            surface = RuntimeCliSurface.TERMINAL_AGENT,
            productBlurb = "终端 Agent：独立 pi 进程；chat 兼容网关（如 dots）在 pi 内配置，不进 Codex",
            versions = listOf(
                RuntimeCliVersion(
                    id = "pi-" + piVersionLabel,
                    label = piVersionLabel,
                    selected = true,
                    downloadBytes = piDownloadBytes,
                    notes = "npm 钉死 @earendil-works/pi-coding-agent；可复用已装 dsh 的 Node",
                ),
            ),
        ),
        RuntimeCliKind(
            id = CLAUDE_CODE,
            displayName = "Claude Code",
            recipeId = "recipe_claude_code",
            available = false,
            comingSoon = true,
            surface = RuntimeCliSurface.TERMINAL_AGENT,
            productBlurb = "终端 Agent：规划中，不会随首次准备下载",
            versions = emptyList(),
        ),
    )

    fun surfaceLabel(surface: RuntimeCliSurface): String = when (surface) {
        RuntimeCliSurface.NATIVE_CHAT -> "原生聊天"
        RuntimeCliSurface.WEB_UI -> "Web UI"
        RuntimeCliSurface.TERMINAL_AGENT -> "终端 Agent"
    }
}
