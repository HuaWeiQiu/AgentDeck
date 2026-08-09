package com.agentdeck.app.domain.launch

import android.annotation.SuppressLint
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
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
                CodexUbuntuAdapter,
                ClaudeTermuxAdapter,
            ),
        )
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
