package com.agentdeck.app.domain.runtime

data class RuntimeCliKind(
    val id: String,
    val displayName: String,
    val recipeId: String,
    val available: Boolean,
    val comingSoon: Boolean,
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
)

object RuntimeCliCatalog {
    const val CODEX = "codex"
    const val DEEPSEEK_HARNESS = "deepseek-harness"
    const val PI = "pi"
    const val CLAUDE_CODE = "claude-code"

    fun kinds(codexVersion: String, downloadBytes: Long): List<RuntimeCliKind> = listOf(
        RuntimeCliKind(
            id = CODEX,
            displayName = "Codex",
            recipeId = "recipe_codex",
            available = true,
            comingSoon = false,
            versions = listOf(
                RuntimeCliVersion(
                    id = "codex-" + codexVersion,
                    label = "Codex " + codexVersion,
                    selected = true,
                    downloadBytes = downloadBytes,
                    notes = "当前已校验版本，聊天协议按此版本对接",
                ),
            ),
        ),
        placeholder(DEEPSEEK_HARNESS, "DeepSeek Harness", "recipe_deepseek_harness"),
        placeholder(PI, "pi", "recipe_pi"),
        placeholder(CLAUDE_CODE, "Claude Code", "recipe_claude_code"),
    )

    private fun placeholder(id: String, name: String, recipeId: String) = RuntimeCliKind(
        id = id,
        displayName = name,
        recipeId = recipeId,
        available = false,
        comingSoon = true,
        versions = emptyList(),
    )
}
