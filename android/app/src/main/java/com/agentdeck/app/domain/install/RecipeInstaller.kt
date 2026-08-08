package com.agentdeck.app.domain.install

import android.annotation.SuppressLint
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.RecipeCommand
import com.agentdeck.app.domain.model.RecipeRuntime
import com.agentdeck.app.domain.recipe.RecipeCatalog
import com.agentdeck.app.domain.recipe.RecipeDependencyResolver

internal object RecipeInstallResultInterpreter {
    fun interpret(result: TermuxCommandResult): Result<String> {
        if (!result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }
                .trim()
                .takeLast(MAX_DETAIL_LENGTH)
                .ifBlank { "未返回错误信息" }
            return Result.failure(
                IllegalStateException("安装失败（退出码 ${result.exitCode}）：$detail"),
            )
        }
        val lastLine = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
            ?.take(MAX_DETAIL_LENGTH)
            ?: "安装命令已完成"
        return Result.success(
            if (result.outputWasTruncated) "$lastLine（较早输出已截断）" else lastLine,
        )
    }

    private const val MAX_DETAIL_LENGTH = 240
}

@SuppressLint("SdCardPath")
class RecipeInstaller(
    private val termux: TermuxGateway,
    private val recipes: RecipeCatalog,
) {
    suspend fun install(recipeId: String): Result<String> {
        val catalog = recipes.loadRecipes()
        val ordered = RecipeDependencyResolver.resolve(catalog, recipeId).getOrElse { error ->
            return Result.failure(error)
        }
        val target = ordered.last()

        for (recipe in ordered) {
            if (!recipe.available) {
                return Result.failure(IllegalStateException("${recipe.name} 尚未开放安装"))
            }
            val verifyCommand = requireNotNull(recipe.verify)
            val before = execute(recipe, verifyCommand, "probe", VERIFY_TIMEOUT_MILLIS)
                .getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("无法检测 ${recipe.name}: ${error.message}", error),
                    )
                }
            if (before.commandSucceeded) continue

            val installCommand = requireNotNull(recipe.install)
            val installScript = buildInstallScript(recipe, installCommand.script).getOrElse { error ->
                return Result.failure(error)
            }
            val installed = execute(
                recipe = recipe,
                command = installCommand.copy(script = installScript),
                purpose = "install",
                timeoutMillis = recipe.timeoutMinutes * 60_000L,
            ).getOrElse { error ->
                return Result.failure(
                    IllegalStateException("${recipe.name} 安装命令失败: ${error.message}", error),
                )
            }
            RecipeInstallResultInterpreter.interpret(installed).getOrElse { error ->
                return Result.failure(IllegalStateException("${recipe.name}: ${error.message}", error))
            }

            val after = execute(recipe, verifyCommand, "verify", VERIFY_TIMEOUT_MILLIS)
                .getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("无法验证 ${recipe.name}: ${error.message}", error),
                    )
                }
            if (!after.commandSucceeded) {
                return RecipeInstallResultInterpreter.interpret(after).fold(
                    onSuccess = { Result.failure(IllegalStateException("${recipe.name} 验证未通过")) },
                    onFailure = { error ->
                        Result.failure(IllegalStateException("${recipe.name} 安装后验证失败: ${error.message}"))
                    },
                )
            }
        }

        return Result.success("${target.name} ${target.version} 已安装并验证")
    }

    private suspend fun execute(
        recipe: AgentRecipe,
        command: RecipeCommand,
        purpose: String,
        timeoutMillis: Long,
    ): Result<TermuxCommandResult> {
        require(command.runtime == RecipeRuntime.TERMUX) { "不支持的配方 runtime" }
        val termuxCommand = TermuxCommand(
            sessionName = sessionName(recipe.id, purpose),
            executable = TERMUX_BASH,
            args = listOf("-c", command.script),
            background = true,
            reuseExistingSession = false,
        )
        return termux.runCommandForResult(termuxCommand, timeoutMillis)
    }

    private fun buildInstallScript(recipe: AgentRecipe, script: String): Result<String> = runCatching {
        val wrapperAsset = recipe.wrapperAsset ?: return@runCatching script
        val body = recipes.readWrapperAsset(wrapperAsset).trimEnd()
        require(body.lineSequence().none { it == WRAPPER_HEREDOC_MARKER }) {
            "wrapper 内容与安装分隔符冲突"
        }
        require(body.startsWith("#!/")) { "wrapper 缺少 shebang" }
        buildString {
            appendLine(script.trimEnd())
            appendLine("mkdir -p \"${'$'}HOME/.agentdeck/wrappers\"")
            appendLine("cat > \"${'$'}HOME/.agentdeck/wrappers/$wrapperAsset\" <<'$WRAPPER_HEREDOC_MARKER'")
            appendLine(body)
            appendLine(WRAPPER_HEREDOC_MARKER)
            appendLine("chmod 700 \"${'$'}HOME/.agentdeck/wrappers/$wrapperAsset\"")
        }.trimEnd()
    }

    private fun sessionName(recipeId: String, purpose: String): String {
        val raw = "agentdeck-$purpose-$recipeId"
        if (raw.length <= MAX_SESSION_NAME_LENGTH) return raw
        val suffix = recipeId.hashCode().toUInt().toString(16).padStart(8, '0')
        return "${raw.take(MAX_SESSION_NAME_LENGTH - suffix.length - 1)}-$suffix"
    }

    companion object {
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val VERIFY_TIMEOUT_MILLIS = 2 * 60 * 1_000L
        private const val MAX_SESSION_NAME_LENGTH = 64
        private const val WRAPPER_HEREDOC_MARKER = "AGENTDECK_WRAPPER_EOF"
    }
}
