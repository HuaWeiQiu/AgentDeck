package com.agentdeck.app.domain.launch

import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.recipe.RecipeCatalog
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand

object LaunchCommandFactory {
    fun create(
        card: AgentCard,
        profile: ProviderProfile? = null,
        defaultPermissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
        registry: CliAdapterRegistry = CliAdapterRegistry.default,
    ): Result<RuntimeCommand> = registry.forCard(card).fold(
        onSuccess = { adapter ->
            adapter.validateProfile(profile).fold(
                onSuccess = {
                    adapter.createCommand(
                        card,
                        CodexPermissionLevel.effective(card.permissionLevel, defaultPermissionLevel),
                    )
                },
                onFailure = { Result.failure(it) },
            )
        },
        onFailure = { Result.failure(it) },
    )
}

class LaunchInteractor(
    private val cards: CardRepository,
    private val profiles: ProfileRepository,
    private val recipes: RecipeCatalog,
    private val runtime: AgentRuntime,
    private val adapters: CliAdapterRegistry = CliAdapterRegistry.default,
) {
    suspend fun launch(
        cardId: String,
        defaultPermissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
    ): LaunchResult {
        val card = cards.getCard(cardId)
            ?: return LaunchResult.Failed("卡片不存在: $cardId")

        val runtimeStatus = runtime.status()
        if (!runtimeStatus.installed) {
            return LaunchResult.Failed("本机运行环境尚未安装，请先到设置页完成准备")
        }
        if (!runtimeStatus.ready) {
            return LaunchResult.Failed(runtimeStatus.detail)
        }

        val recipe = recipes.loadRecipes().firstOrNull { it.id == card.recipeId }
            ?: return LaunchResult.Failed("卡片引用了未知配方: ${card.recipeId}")
        if (!recipe.available) {
            return LaunchResult.Failed("${recipe.name} 尚未开放，请删除该卡片或等待适配完成")
        }
        val profile = card.profileId?.let { profileId ->
            profiles.getProfile(profileId)
                ?: return LaunchResult.Failed("卡片绑定的 CLI 配置已不存在，请重新编辑卡片")
        }
        val command = LaunchCommandFactory.create(
            card,
            profile,
            defaultPermissionLevel,
            adapters,
        ).getOrElse {
            return LaunchResult.Failed(it.message ?: "启动配置无效")
        }
        return foregroundLaunchResult(
            commandResult = runtime.runCommand(command),
            openTermux = runtime::openConsole,
        )
    }

    fun wrapperBootstrapCommand(): String {
        val body = recipes.readWrapperAsset("codex-ubuntu.sh").trimEnd()
        return buildString {
            appendLine("mkdir -p ~/.agentdeck/wrappers")
            appendLine("cat > ~/.agentdeck/wrappers/codex-ubuntu.sh <<'AGENTDECK_EOF'")
            appendLine(body)
            appendLine("AGENTDECK_EOF")
            appendLine("chmod 700 ~/.agentdeck/wrappers/codex-ubuntu.sh")
            append("echo \"wrapper installed\"")
        }
    }
}

internal fun foregroundLaunchResult(
    commandResult: Result<Unit>,
    openTermux: () -> Boolean,
): LaunchResult = commandResult.fold(
    onSuccess = {
        if (openTermux()) {
            LaunchResult.Success
        } else {
            LaunchResult.Failed("命令已发送，但无法打开 Termux，请从桌面手动打开")
        }
    },
    onFailure = { LaunchResult.Failed(it.message ?: "启动失败") },
)
