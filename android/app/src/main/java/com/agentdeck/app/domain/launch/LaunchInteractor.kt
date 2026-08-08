package com.agentdeck.app.domain.launch

import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.recipe.RecipeCatalog

object LaunchCommandFactory {
    fun create(
        card: AgentCard,
        profile: ProviderProfile? = null,
        registry: CliAdapterRegistry = CliAdapterRegistry.default,
    ): Result<TermuxCommand> = registry.forCard(card).fold(
        onSuccess = { adapter ->
            adapter.validateProfile(profile).fold(
                onSuccess = { adapter.createCommand(card) },
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
    private val termux: TermuxGateway,
    private val adapters: CliAdapterRegistry = CliAdapterRegistry.default,
) {
    suspend fun launch(cardId: String): LaunchResult {
        val card = cards.getCard(cardId)
            ?: return LaunchResult.Failed("卡片不存在: $cardId")

        if (!termux.isTermuxInstalled()) {
            return LaunchResult.Failed("未安装 Termux，请先到设置页安装 F-Droid 版")
        }
        if (!termux.hasRunCommandPermission()) {
            return LaunchResult.Failed("尚未授予 Termux RUN_COMMAND 权限，请到设置页处理")
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
        val command = LaunchCommandFactory.create(card, profile, adapters).getOrElse {
            return LaunchResult.Failed(it.message ?: "启动配置无效")
        }
        return termux.runCommand(command).fold(
            onSuccess = { LaunchResult.Success },
            onFailure = { LaunchResult.Failed(it.message ?: "启动失败") },
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
