package com.agentdeck.app.domain.launch

import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.repo.RecipeRepository
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.ProviderType

/**
 * Resolve card + profile, map env vars, invoke Termux wrapper.
 *
 * For Codex (P0): run ~/.agentdeck/wrappers/codex-ubuntu.sh which does
 * proot-distro login ubuntu → cd workspace → codex
 */
class LaunchInteractor(
    private val cards: CardRepository,
    private val profiles: ProfileRepository,
    private val recipes: RecipeRepository,
    private val termux: TermuxGateway,
) {
    suspend fun launch(cardId: String): LaunchResult {
        val card = cards.getCard(cardId)
            ?: return LaunchResult.Failed("卡片不存在: $cardId")

        if (!termux.isTermuxInstalled()) {
            return LaunchResult.Failed("未安装 Termux，请先到设置页安装 F-Droid 版")
        }

        val profile = card.profileId?.let { profiles.getProfile(it) }
        val apiKey = profile?.let { profiles.getApiKey(it) }

        val env = mutableMapOf<String, String>()
        env["AGENTDECK_DISTRO"] = card.distro
        env["AGENTDECK_INNER_CWD"] = card.workspacePath
        env["AGENTDECK_INNER_BIN"] = card.innerBin

        if (profile != null) {
            when (profile.type) {
                ProviderType.OPENAI_COMPATIBLE -> {
                    if (!apiKey.isNullOrBlank()) env["OPENAI_API_KEY"] = apiKey
                    if (profile.baseUrl.isNotBlank()) env["OPENAI_BASE_URL"] = profile.baseUrl
                    if (profile.defaultModel.isNotBlank()) env["OPENAI_MODEL"] = profile.defaultModel
                    // Also expose AgentDeck-prefixed copies for wrapper scripts
                    if (!apiKey.isNullOrBlank()) env["AGENTDECK_OPENAI_API_KEY"] = apiKey
                    if (profile.baseUrl.isNotBlank()) env["AGENTDECK_OPENAI_BASE_URL"] = profile.baseUrl
                    if (profile.defaultModel.isNotBlank()) env["AGENTDECK_MODEL"] = profile.defaultModel
                }
                ProviderType.ANTHROPIC -> {
                    if (!apiKey.isNullOrBlank()) env["ANTHROPIC_API_KEY"] = apiKey
                    if (profile.baseUrl.isNotBlank()) env["ANTHROPIC_BASE_URL"] = profile.baseUrl
                    if (!apiKey.isNullOrBlank()) env["AGENTDECK_ANTHROPIC_API_KEY"] = apiKey
                    if (profile.baseUrl.isNotBlank()) env["AGENTDECK_ANTHROPIC_BASE_URL"] = profile.baseUrl
                    if (profile.defaultModel.isNotBlank()) env["AGENTDECK_MODEL"] = profile.defaultModel
                }
            }
        }

        val wrapperPath = "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-ubuntu.sh"
        val command: String = when (card.templateId) {
            "tpl_codex_ubuntu" -> {
                val inline = buildInlineCodexCommand(
                    distro = card.distro,
                    cwd = card.workspacePath,
                    bin = card.innerBin,
                    innerArgs = card.innerArgs,
                )
                val extra = card.innerArgs.joinToString(" ") { shellQuote(it) }
                """
                    if [ -x ${shellQuote(wrapperPath)} ]; then
                      exec ${shellQuote(wrapperPath)} $extra
                    else
                      $inline
                    fi
                """.trimIndent().replace('\n', ' ')
            }
            "tpl_claude_termux" -> buildString {
                append("cd ${shellQuote(card.workspacePath)} && ")
                append("command -v claude >/dev/null || { echo 'claude not found'; exit 127; }; ")
                append("exec claude")
                card.innerArgs.forEach { append(' ').append(shellQuote(it)) }
            }
            else -> {
                "cd ${shellQuote(card.workspacePath)} && exec ${shellQuote(card.innerBin)}"
            }
        }

        val result = termux.runCommand(
            sessionName = card.termuxSessionName,
            executable = "/data/data/com.termux/files/usr/bin/bash",
            args = listOf("-lc", command),
            workDir = "/data/data/com.termux/files/home",
            env = env,
            background = false,
        )

        return result.fold(
            onSuccess = { LaunchResult.Success },
            onFailure = { LaunchResult.Failed(it.message ?: "启动失败") },
        )
    }

    /**
     * Best-effort: push wrapper content into Termux via a setup command.
     * Full file install belongs to RecipeInstaller (P1); skeleton exposes helper text.
     */
    fun wrapperBootstrapCommand(): String {
        val body = recipes.readWrapperAsset("codex-ubuntu.sh").trimEnd()
        return buildString {
            appendLine("mkdir -p ~/.agentdeck/wrappers ~/.agentdeck/run")
            appendLine("cat > ~/.agentdeck/wrappers/codex-ubuntu.sh <<'AGENTDECK_EOF'")
            appendLine(body)
            appendLine("AGENTDECK_EOF")
            appendLine("chmod +x ~/.agentdeck/wrappers/codex-ubuntu.sh")
            append("echo \"wrapper installed\"")
        }
    }

    private fun buildInlineCodexCommand(
        distro: String,
        cwd: String,
        bin: String,
        innerArgs: List<String>,
    ): String {
        val args = innerArgs.joinToString(" ") { shellQuote(it) }
        return """
            command -v proot-distro >/dev/null || { echo 'proot-distro missing'; exit 127; }
            exec proot-distro login ${shellQuote(distro)} -- bash -lc 'mkdir -p ${shellQuote(cwd)} && cd ${shellQuote(cwd)} && exec ${shellQuote(bin)} $args'
        """.trimIndent().replace('\n', ' ')
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
