package com.agentdeck.app.domain.install

import android.annotation.SuppressLint
import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.RecipeCommand
import com.agentdeck.app.domain.model.RecipeRuntime
import com.agentdeck.app.domain.recipe.RecipeCatalog
import com.agentdeck.app.domain.recipe.RecipeDependencyResolver
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import com.agentdeck.app.domain.runtime.RuntimeKind
import com.agentdeck.app.domain.runtime.RuntimeProgram

enum class InstallPhase {
    PROBING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING,
    INSTALLING_TOOLS,
    VERIFYING,
    COMPLETE,
}

data class RecipeInstallProgress(
    val recipeId: String,
    val recipeName: String,
    val recipeIndex: Int,
    val recipeCount: Int,
    val phase: InstallPhase,
)

interface RecipeInstallation {
    suspend fun install(
        recipeId: String,
        onProgress: (RecipeInstallProgress) -> Unit = {},
    ): Result<String>
}

internal object RecipeInstallResultInterpreter {
    fun interpret(result: RuntimeCommandResult): Result<String> {
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
    private val runtime: AgentRuntime,
    private val recipes: RecipeCatalog,
) : RecipeInstallation {
    override suspend fun install(
        recipeId: String,
        onProgress: (RecipeInstallProgress) -> Unit,
    ): Result<String> {
        val catalog = recipes.loadRecipes()
        val ordered = RecipeDependencyResolver.resolve(catalog, recipeId).getOrElse { error ->
            return Result.failure(error)
        }
        val target = ordered.last()

        for ((index, recipe) in ordered.withIndex()) {
            if (!recipe.available) {
                return Result.failure(IllegalStateException("${recipe.name} 尚未开放安装"))
            }
            onProgress(recipe.progress(index, ordered.size, InstallPhase.PROBING))
            val verifyCommand = requireNotNull(recipe.verify)
            val before = execute(recipe, verifyCommand, "probe", VERIFY_TIMEOUT_MILLIS)
                .getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("无法检测 ${recipe.name}: ${error.message}", error),
                    )
                }
            if (before.commandSucceeded) {
                onProgress(recipe.progress(index, ordered.size, InstallPhase.COMPLETE))
                continue
            }

            val installCommand = requireNotNull(recipe.install)
            val installScript = buildInstallScript(recipe, installCommand.script).getOrElse { error ->
                return Result.failure(error)
            }
            onProgress(recipe.progress(index, ordered.size, InstallPhase.INSTALLING))
            val installed = execute(
                recipe = recipe,
                command = installCommand.copy(script = withInstallLock(installScript)),
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

            onProgress(recipe.progress(index, ordered.size, InstallPhase.VERIFYING))
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
            onProgress(recipe.progress(index, ordered.size, InstallPhase.COMPLETE))
        }

        return Result.success("${target.name} 已可用并验证")
    }

    private suspend fun execute(
        recipe: AgentRecipe,
        command: RecipeCommand,
        purpose: String,
        timeoutMillis: Long,
    ): Result<RuntimeCommandResult> {
        require(command.runtime == RecipeRuntime.TERMUX) { "不支持的配方 runtime" }
        require(runtime.kind == RuntimeKind.TERMUX_COMPATIBILITY) { "配方仅支持 Termux 兼容运行环境" }
        val runtimeCommand = RuntimeCommand(
            instanceId = sessionName(recipe.id, purpose),
            program = RuntimeProgram.HOST_SHELL,
            script = command.script,
            background = true,
            reuseExistingInstance = false,
        )
        return runtime.runCommandForResult(runtimeCommand, timeoutMillis)
    }

    private fun buildInstallScript(recipe: AgentRecipe, script: String): Result<String> = runCatching {
        val wrapperAssets = listOfNotNull(recipe.wrapperAsset) + recipe.additionalWrapperAssets
        if (wrapperAssets.isEmpty()) return@runCatching script
        val bodies = wrapperAssets.associateWith { asset ->
            recipes.readWrapperAsset(asset).trimEnd().also { body ->
                require(body.lineSequence().none { it == WRAPPER_HEREDOC_MARKER }) {
                    "wrapper 内容与安装分隔符冲突"
                }
                require(body.startsWith("#!/")) { "$asset 缺少 shebang" }
            }
        }
        buildString {
            appendLine(script.trimEnd())
            appendLine("mkdir -p \"${'$'}HOME/.agentdeck/wrappers\"")
            bodies.forEach { (asset, body) ->
                appendLine("cat > \"${'$'}HOME/.agentdeck/wrappers/$asset\" <<'$WRAPPER_HEREDOC_MARKER'")
                appendLine(body)
                appendLine(WRAPPER_HEREDOC_MARKER)
                appendLine("chmod 700 \"${'$'}HOME/.agentdeck/wrappers/$asset\"")
            }
        }.trimEnd()
    }

    private fun withInstallLock(script: String): String = """
        set -euo pipefail
        agentdeck_root="${'$'}HOME/.agentdeck"
        agentdeck_lock="${'$'}agentdeck_root/install.lock"
        mkdir -p "${'$'}agentdeck_root"

        acquire_agentdeck_lock() {
          if mkdir "${'$'}agentdeck_lock" 2>/dev/null; then
            printf '%s\n' "${'$'}${'$'}" > "${'$'}agentdeck_lock/pid"
            return 0
          fi

          if [[ -L "${'$'}agentdeck_lock" ]]; then
            echo "AgentDeck 安装锁路径异常，请检查 ${'$'}agentdeck_lock" >&2
            return 75
          fi

          existing_pid="${'$'}(cat "${'$'}agentdeck_lock/pid" 2>/dev/null || true)"
          if [[ "${'$'}existing_pid" =~ ^[0-9]+${'$'} ]] && kill -0 "${'$'}existing_pid" 2>/dev/null; then
            echo "另一个 AgentDeck 安装任务仍在运行（PID ${'$'}existing_pid）" >&2
            return 75
          fi

          stale_lock="${'$'}agentdeck_root/install.lock.stale.${'$'}${'$'}"
          if ! mv "${'$'}agentdeck_lock" "${'$'}stale_lock" 2>/dev/null; then
            echo "安装锁状态已变化，请稍后重试" >&2
            return 75
          fi
          rm -f -- "${'$'}stale_lock/pid"
          rmdir -- "${'$'}stale_lock" 2>/dev/null || {
            echo "陈旧安装锁包含未知内容，请检查 ${'$'}stale_lock" >&2
            return 75
          }
          mkdir "${'$'}agentdeck_lock" 2>/dev/null || {
            echo "另一个 AgentDeck 安装任务刚刚启动" >&2
            return 75
          }
          printf '%s\n' "${'$'}${'$'}" > "${'$'}agentdeck_lock/pid"
        }

        release_agentdeck_lock() {
          current_pid="${'$'}(cat "${'$'}agentdeck_lock/pid" 2>/dev/null || true)"
          if [[ "${'$'}current_pid" == "${'$'}${'$'}" ]]; then
            rm -f -- "${'$'}agentdeck_lock/pid"
            rmdir -- "${'$'}agentdeck_lock" 2>/dev/null || true
          fi
        }

        acquire_agentdeck_lock
        trap release_agentdeck_lock EXIT
        trap 'exit 129' HUP
        trap 'exit 130' INT
        trap 'exit 143' TERM

        ${script.trimEnd()}
    """.trimIndent()

    private fun sessionName(recipeId: String, purpose: String): String {
        val raw = "agentdeck-$purpose-$recipeId"
        if (raw.length <= MAX_SESSION_NAME_LENGTH) return raw
        val suffix = recipeId.hashCode().toUInt().toString(16).padStart(8, '0')
        return "${raw.take(MAX_SESSION_NAME_LENGTH - suffix.length - 1)}-$suffix"
    }

    companion object {
        private const val VERIFY_TIMEOUT_MILLIS = 2 * 60 * 1_000L
        private const val MAX_SESSION_NAME_LENGTH = 64
        private const val WRAPPER_HEREDOC_MARKER = "AGENTDECK_WRAPPER_EOF"
    }
}

private fun AgentRecipe.progress(
    index: Int,
    count: Int,
    phase: InstallPhase,
) = RecipeInstallProgress(
    recipeId = id,
    recipeName = name,
    recipeIndex = index,
    recipeCount = count,
    phase = phase,
)
