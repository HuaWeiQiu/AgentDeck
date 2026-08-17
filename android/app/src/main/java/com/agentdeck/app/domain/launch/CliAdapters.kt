package com.agentdeck.app.domain.launch

import android.annotation.SuppressLint
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram

data class CliAdapterDescriptor(
    val recipeId: String,
    val templateId: String,
    val displayName: String,
    val providerType: ProviderType,
    val defaultWorkspaceNamespace: PathNamespace,
    val defaultWorkspacePath: String,
    val defaultIcon: String,
    val defaultInnerBin: String,
)

interface CliAdapter {
    val descriptor: CliAdapterDescriptor
    fun validateCard(card: AgentCard): Result<Unit>
    fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
    ): Result<RuntimeCommand>

    fun validateProfile(profile: ProviderProfile?): Result<Unit> = runCatching {
        if (profile != null) {
            require(profile.type == descriptor.providerType) {
                "${descriptor.displayName} 只能绑定 ${descriptor.providerType.displayName()} 配置"
            }
        }
    }
}

class CliAdapterRegistry(
    adapters: List<CliAdapter>,
) {
    private val byTemplate = adapters.associateBy { it.descriptor.templateId }.also {
        require(it.size == adapters.size) { "CLI adapter templateId 重复" }
    }
    private val byRecipe = adapters.associateBy { it.descriptor.recipeId }.also {
        require(it.size == adapters.size) { "CLI adapter recipeId 重复" }
    }

    fun forCard(card: AgentCard): Result<CliAdapter> = runCatching {
        val adapter = byTemplate[card.templateId]
            ?: error("不支持的启动模板: ${card.templateId}")
        require(adapter.descriptor.recipeId == card.recipeId) {
            "卡片配方与启动模板不匹配"
        }
        adapter
    }

    fun forRecipe(recipeId: String): CliAdapter? = byRecipe[recipeId]

    fun descriptors(): List<CliAdapterDescriptor> = byRecipe.values.map { it.descriptor }

    companion object {
        val default = CliAdapterRegistry(
            listOf(
                LightChatAdapter,
                CodexUbuntuAdapter,
                DshWebAdapter,
                PiAgentAdapter,
                ClaudeTermuxAdapter,
            ),
        )

        /**
         * Session-create order (product modes):
         * - [recipe_light] 轻聊：无 PRoot / 无本地 Agent runtime，可写角色
         * - [recipe_codex] / pi / dsh 开发：完整工具链
         * Lab flavor is the separate “狂暴” surface — never mixed into Secure recipes.
         */
        val SESSION_RECIPE_ORDER: List<String> = listOf(
            "recipe_light",
            "recipe_codex",
            "recipe_pi",
            "recipe_deepseek_harness",
        )

        val SESSION_RECIPE_IDS: Set<String> = SESSION_RECIPE_ORDER.toSet()

        fun isSessionRecipe(recipeId: String): Boolean = recipeId in SESSION_RECIPE_IDS

        fun requiresCodexNativeChat(recipeId: String): Boolean = recipeId == "recipe_codex"

        /** dsh: models configured in-web, not via AgentDeck profile picker. */
        fun usesExternalAgentUi(recipeId: String): Boolean =
            recipeId == "recipe_deepseek_harness"

        /** pi: native chat; binds AgentDeck Chat Completions profiles. */
        fun usesPiNativeChat(recipeId: String): Boolean = recipeId == "recipe_pi"

        /** 轻聊：OkHttp Chat Completions only — no embedded runtime. */
        fun usesLightChat(recipeId: String): Boolean = recipeId == "recipe_light"

        /** Product mode bucket for create/edit UI. */
        fun isLightMode(recipeId: String): Boolean = usesLightChat(recipeId)

        fun isDevMode(recipeId: String): Boolean =
            isSessionRecipe(recipeId) && !usesLightChat(recipeId)
    }
}

@SuppressLint("SdCardPath")
object CodexUbuntuAdapter : CliAdapter {
    override val descriptor = CliAdapterDescriptor(
        recipeId = "recipe_codex",
        templateId = "tpl_codex_ubuntu",
        displayName = "Codex",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        defaultWorkspaceNamespace = PathNamespace.UBUNTU,
        defaultWorkspacePath = "/root/projects/default",
        defaultIcon = "codex",
        defaultInnerBin = "codex",
    )

    override fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel,
    ): Result<RuntimeCommand> = runCatching {
        require(card.enabled) { "卡片已停用" }
        validateCard(card).getOrThrow()
        val approvalPolicy = requireNotNull(permissionLevel.terminalApprovalPolicy) {
            "只读权限不会打开终端；请在对话中查看文件，或先更改权限等级"
        }
        RuntimeCommand(
            instanceId = card.termuxSessionName,
            program = RuntimeProgram.CODEX_TERMINAL,
            args = buildList {
                addAll(listOf("--distro", card.distro))
                addAll(listOf("--cwd", card.workspacePath))
                addAll(listOf("--bin", card.innerBin))
                addAll(listOf("--approval-policy", approvalPolicy))
                add("--")
                addAll(card.innerArgs)
            },
        )
    }

    override fun validateCard(card: AgentCard): Result<Unit> = runCatching {
        validateCommon(card)
        require(card.workspaceNamespace == PathNamespace.UBUNTU) {
            "Codex Ubuntu 模板需要 Ubuntu 工作目录"
        }
        require(card.distro == "ubuntu") { "Codex 配方只支持 ubuntu 发行版" }
        require(card.innerBin == "codex") { "Codex 模板只允许启动 codex" }
    }
}

/**
 * DeepSeek Harness session card → App opens managed dsh Web UI (not Codex app-server).
 * Install lives in [com.agentdeck.app.data.runtime.DshRuntimeInstaller], not RecipeInstaller.
 */
object DshWebAdapter : CliAdapter {
    override val descriptor = CliAdapterDescriptor(
        recipeId = "recipe_deepseek_harness",
        templateId = "tpl_dsh_web",
        displayName = "DeepSeek Harness (dsh)",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        defaultWorkspaceNamespace = PathNamespace.UBUNTU,
        defaultWorkspacePath = "/root/projects/default",
        defaultIcon = "dsh",
        defaultInnerBin = "dsh",
    )

    override fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel,
    ): Result<RuntimeCommand> = Result.failure(
        IllegalStateException("dsh 请从对话列表打开网页，不要走终端启动"),
    )

    override fun validateCard(card: AgentCard): Result<Unit> = runCatching {
        validateCommon(card)
        require(card.workspaceNamespace == PathNamespace.UBUNTU) {
            "dsh 需要 Ubuntu 工作区命名空间"
        }
        require(card.innerBin == "dsh") { "dsh 模板只允许 innerBin=dsh" }
    }

    override fun validateProfile(profile: ProviderProfile?): Result<Unit> = runCatching {
        // dsh can still use its own web settings; optional AgentDeck chat profile is allowed later.
        if (profile != null) {
            require(profile.type == descriptor.providerType) {
                "dsh 只能绑定 OpenAI 兼容模型服务"
            }
        }
    }
}

/**
 * pi coding agent session card → install/smoke from runtime; full terminal later (D3).
 */
object PiAgentAdapter : CliAdapter {
    override val descriptor = CliAdapterDescriptor(
        recipeId = "recipe_pi",
        templateId = "tpl_pi_agent",
        displayName = "pi",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        defaultWorkspaceNamespace = PathNamespace.UBUNTU,
        defaultWorkspacePath = "/root/projects/default",
        defaultIcon = "pi",
        defaultInnerBin = "pi",
    )

    override fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel,
    ): Result<RuntimeCommand> = Result.failure(
        IllegalStateException("pi 请从对话列表进入；完整终端壳尚未开放"),
    )

    override fun validateCard(card: AgentCard): Result<Unit> = runCatching {
        validateCommon(card)
        require(card.workspaceNamespace == PathNamespace.UBUNTU) {
            "pi 需要 Ubuntu 工作区命名空间"
        }
        require(card.innerBin == "pi") { "pi 模板只允许 innerBin=pi" }
    }

    override fun validateProfile(profile: ProviderProfile?): Result<Unit> = runCatching {
        // Prefer AgentDeck「Chat Completions」profiles; null = user must configure in models.
        if (profile != null) {
            require(profile.type == descriptor.providerType) {
                "pi 只能绑定 OpenAI 兼容模型服务"
            }
            require(profile.adapterId.isChatCompletionsCompatible()) {
                "pi 请绑定「Chat Completions」类型的模型服务（如 dots），不要选 Responses"
            }
        }
    }
}

/**
 * 轻聊：直连 Chat Completions（dots 等），不启动 Codex / pi / PRoot。
 * 支持会话级角色 identity；无工具、无权限档、无扩展。
 */
@SuppressLint("SdCardPath")
object LightChatAdapter : CliAdapter {
    override val descriptor = CliAdapterDescriptor(
        recipeId = "recipe_light",
        templateId = "tpl_light_chat",
        displayName = "轻聊",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        defaultWorkspaceNamespace = PathNamespace.UBUNTU,
        defaultWorkspacePath = "/root/projects/light",
        defaultIcon = "chat",
        defaultInnerBin = "light",
    )

    override fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel,
    ): Result<RuntimeCommand> = Result.failure(
        IllegalStateException("轻聊不使用本地 runtime，请从对话列表进入"),
    )

    override fun validateCard(card: AgentCard): Result<Unit> = runCatching {
        validateCommon(card)
        require(card.innerBin == "light") { "轻聊模板只允许 innerBin=light" }
        require(card.permissionLevel == null) { "轻聊没有本地权限档" }
    }

    override fun validateProfile(profile: ProviderProfile?): Result<Unit> = runCatching {
        requireNotNull(profile) { "请为轻聊选择「Chat Completions」模型服务" }
        require(profile.type == descriptor.providerType) {
            "轻聊只能绑定 OpenAI 兼容模型服务"
        }
        require(profile.adapterId.isChatCompletionsCompatible()) {
            "轻聊请绑定「Chat Completions」类型（如 dots），不要选 Responses"
        }
    }
}

@SuppressLint("SdCardPath")
object ClaudeTermuxAdapter : CliAdapter {
    override val descriptor = CliAdapterDescriptor(
        recipeId = "recipe_claude_code",
        templateId = "tpl_claude_termux",
        displayName = "Claude Code",
        providerType = ProviderType.ANTHROPIC,
        defaultWorkspaceNamespace = PathNamespace.TERMUX,
        defaultWorkspacePath = "/data/data/com.termux/files/home",
        defaultIcon = "claude",
        defaultInnerBin = "claude",
    )

    override fun createCommand(
        card: AgentCard,
        permissionLevel: CodexPermissionLevel,
    ): Result<RuntimeCommand> = runCatching {
        require(card.enabled) { "卡片已停用" }
        validateCard(card).getOrThrow()
        RuntimeCommand(
            instanceId = card.termuxSessionName,
            program = RuntimeProgram.CLAUDE_TERMINAL,
            args = card.innerArgs,
            workDir = card.workspacePath,
        )
    }

    override fun validateCard(card: AgentCard): Result<Unit> = runCatching {
        validateCommon(card)
        require(card.workspaceNamespace == PathNamespace.TERMUX) {
            "Claude Termux 模板需要 Termux 工作目录"
        }
        require(card.innerBin == "claude") { "Claude 模板只允许启动 claude" }
    }
}

private fun validateCommon(card: AgentCard) {
    require(card.name.isNotBlank() && card.name.length <= 80) { "卡片名称无效" }
    require(card.termuxSessionName.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
        "Termux 会话名无效"
    }
    require(card.workspacePath.startsWith('/') && card.workspacePath.length <= 1_024) {
        "工作目录必须是有效的绝对路径"
    }
    require(card.workspacePath.none { it == '\u0000' || it == '\n' || it == '\r' }) {
        "工作目录包含非法字符"
    }
    require(card.innerArgs.size <= 64 && card.innerArgs.all { it.length <= 2_048 && '\u0000' !in it }) {
        "CLI 参数无效"
    }
}

private fun ProviderType.displayName(): String = when (this) {
    ProviderType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderType.ANTHROPIC -> "Anthropic"
}
