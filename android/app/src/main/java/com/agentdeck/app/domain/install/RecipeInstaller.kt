package com.agentdeck.app.domain.install

import com.agentdeck.app.data.repo.RecipeRepository
import com.agentdeck.app.data.termux.TermuxGateway

/**
 * Skeleton installer: opens a Termux session that prints the recipe plan.
 * Full multi-step YAML execution is P1.
 */
class RecipeInstaller(
    private val termux: TermuxGateway,
    private val recipes: RecipeRepository,
) {
    fun install(recipeId: String): Result<Unit> {
        val recipe = recipes.loadRecipes().firstOrNull { it.id == recipeId }
            ?: return Result.failure(IllegalArgumentException("未知配方: $recipeId"))

        val script = when (recipeId) {
            "recipe_proot_ubuntu" -> """
                set -e
                pkg update -y
                pkg install -y proot-distro
                proot-distro install ubuntu || true
                echo "base ubuntu recipe finished"
            """.trimIndent()
            "recipe_codex" -> """
                set -e
                command -v proot-distro >/dev/null
                proot-distro login ubuntu -- bash -lc '
                  set -e
                  if command -v npm >/dev/null; then
                    npm install -g @openai/codex || npm install -g codex
                  else
                    echo "npm missing inside ubuntu — install node first"
                    exit 1
                  fi
                  command -v codex
                '
                mkdir -p ~/.agentdeck/wrappers
                echo "codex recipe finished (install wrapper from settings if needed)"
            """.trimIndent()
            "recipe_claude_code" -> """
                set -e
                npm install -g @anthropic-ai/claude-code
                command -v claude
            """.trimIndent()
            else -> """
                echo "No install script wired for $recipeId yet"
                exit 1
            """.trimIndent()
        }

        return termux.runCommand(
            sessionName = "agentdeck-install-$recipeId",
            executable = "/data/data/com.termux/files/usr/bin/bash",
            args = listOf("-lc", script),
            workDir = "/data/data/com.termux/files/home",
            background = false,
        )
    }
}
