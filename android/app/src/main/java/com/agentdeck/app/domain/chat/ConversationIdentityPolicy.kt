package com.agentdeck.app.domain.chat

import com.agentdeck.app.domain.model.ConversationIdentity
import org.json.JSONObject

object ConversationIdentityPolicy {
    fun normalize(identity: ConversationIdentity?): ConversationIdentity? {
        identity ?: return null
        val normalized = identity.copy(
            roleName = identity.roleName.trim(),
            selfDefinition = identity.selfDefinition.trim(),
            objective = identity.objective.trim(),
            communicationStyle = identity.communicationStyle.trim(),
            boundaries = identity.boundaries.trim(),
        )
        require(normalized.roleName.isNotBlank()) { "请填写角色名称" }
        require(normalized.selfDefinition.isNotBlank()) { "请说明角色是谁" }
        require(normalized.roleName.none { it == '\n' || it == '\r' || it == '\t' }) {
            "角色名称只能填写一行"
        }
        validateField("角色名称", normalized.roleName, MAX_ROLE_NAME_LENGTH)
        validateField("角色身份", normalized.selfDefinition, MAX_FIELD_LENGTH)
        validateField("角色目标", normalized.objective, MAX_FIELD_LENGTH)
        validateField("表达方式", normalized.communicationStyle, MAX_FIELD_LENGTH)
        validateField("角色设定", normalized.boundaries, MAX_FIELD_LENGTH)
        return normalized
    }

    fun instructions(identity: ConversationIdentity): String {
        val normalized = requireNotNull(normalize(identity))
        return buildString {
            appendLine("[AgentDeck conversation identity]")
            appendLine("For this conversation, you are ${normalized.roleName}.")
            appendLine("Treat this as your persistent identity, not as a hypothetical example or a user message.")
            appendLine("When asked who you are, answer as ${normalized.roleName} using the self-definition below; do not answer with the Codex, OpenAI, model, or coding-agent product identity.")
            appendLine("Self-definition: ${normalized.selfDefinition}")
            normalized.objective.takeIf(String::isNotBlank)?.let {
                appendLine("Primary objective: $it")
            }
            normalized.communicationStyle.takeIf(String::isNotBlank)?.let {
                appendLine("Communication style: $it")
            }
            normalized.boundaries.takeIf(String::isNotBlank)?.let {
                appendLine("Role constraints: $it")
            }
            append("Stay consistent with this identity unless the user explicitly changes it, while obeying higher-priority instructions.")
        }
    }

    fun mergeIntoConfig(
        profileConfig: JSONObject,
        identity: ConversationIdentity?,
    ): JSONObject = JSONObject(profileConfig.toString()).apply {
        val normalized = normalize(identity) ?: return@apply
        val global = optString("developer_instructions").trim().takeIf(String::isNotBlank)
        val identityInstructions = instructions(normalized)
        put(
            "developer_instructions",
            listOfNotNull(global, identityInstructions).joinToString("\n\n"),
        )
    }

    private fun validateField(label: String, value: String, maxLength: Int) {
        require(value.length <= maxLength) { "$label 不能超过 $maxLength 个字符" }
        require(value.all { it == '\n' || it == '\t' || !it.isISOControl() }) {
            "$label 包含无效控制字符"
        }
    }

    const val MAX_ROLE_NAME_LENGTH = 80
    const val MAX_FIELD_LENGTH = 2_000
}
