package com.agentdeck.app.domain.backup

import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.PathNamespace
import org.json.JSONArray
import org.json.JSONObject

data class ConversationBackupItem(
    val id: String,
    val name: String,
    val customTitle: String?,
    val recipeId: String,
    val profileId: String?,
    val modelId: String?,
    val permissionLevel: String?,
    val workspacePath: String,
    val pinned: Boolean,
    val archived: Boolean,
    val identity: ConversationIdentity?,
    val selectedExtensionIds: List<String>,
)

data class ConversationBackupDocument(
    val exportedAtEpochMs: Long,
    val conversations: List<ConversationBackupItem>,
)

object ConversationBackupCodec {
    const val FORMAT = "agentdeck.conversation-backup"
    const val VERSION = 1

    fun encode(document: ConversationBackupDocument): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAtEpochMs", document.exportedAtEpochMs)
        val items = JSONArray()
        document.conversations.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("customTitle", item.customTitle)
                    .put("recipeId", item.recipeId)
                    .put("profileId", item.profileId)
                    .put("modelId", item.modelId)
                    .put("permissionLevel", item.permissionLevel)
                    .put("workspacePath", item.workspacePath)
                    .put("pinned", item.pinned)
                    .put("archived", item.archived)
                    .put("selectedExtensionIds", JSONArray(item.selectedExtensionIds))
                    .put(
                        "identity",
                        item.identity?.let { identity ->
                            JSONObject()
                                .put("roleName", identity.roleName)
                                .put("selfDefinition", identity.selfDefinition)
                                .put("objective", identity.objective)
                                .put("communicationStyle", identity.communicationStyle)
                                .put("boundaries", identity.boundaries)
                        },
                    ),
            )
        }
        root.put("conversations", items)
        return root.toString(2)
    }

    fun decode(raw: String): ConversationBackupDocument {
        val root = JSONObject(raw)
        require(root.optString("format") == FORMAT) { "这不是 AgentDeck 的会话备份文件" }
        require(root.optInt("version") == VERSION) { "备份版本不受支持" }
        val exportedAt = root.optLong("exportedAtEpochMs")
        require(exportedAt > 0L) { "备份时间无效" }
        val items = root.optJSONArray("conversations") ?: JSONArray()
        val conversations = buildList {
            for (index in 0 until items.length()) {
                val value = items.getJSONObject(index)
                val identityObject = value.optJSONObject("identity")
                val identity = identityObject?.let {
                    ConversationIdentityPolicy.normalize(
                        ConversationIdentity(
                            roleName = it.optString("roleName"),
                            selfDefinition = it.optString("selfDefinition"),
                            objective = it.optString("objective"),
                            communicationStyle = it.optString("communicationStyle"),
                            boundaries = it.optString("boundaries"),
                        ),
                    )
                }
                val extensionIds = value.optJSONArray("selectedExtensionIds") ?: JSONArray()
                add(
                    ConversationBackupItem(
                        id = value.getString("id").trim().also { require(it.isNotBlank()) { "会话 id 为空" } },
                        name = value.optString("name").ifBlank { "会话" },
                        customTitle = value.optString("customTitle").takeIf { it.isNotBlank() },
                        recipeId = value.optString("recipeId").ifBlank { "recipe_codex" },
                        profileId = value.optString("profileId").takeIf { it.isNotBlank() },
                        modelId = value.optString("modelId").takeIf { it.isNotBlank() },
                        permissionLevel = value.optString("permissionLevel").takeIf { it.isNotBlank() },
                        workspacePath = value.optString("workspacePath").ifBlank { "/root/projects/default" },
                        pinned = value.optBoolean("pinned"),
                        archived = value.optBoolean("archived"),
                        identity = identity,
                        selectedExtensionIds = buildList {
                            for (extIndex in 0 until extensionIds.length()) {
                                val id = extensionIds.optString(extIndex).trim()
                                if (id.isNotBlank()) add(id)
                            }
                        },
                    ),
                )
            }
        }
        return ConversationBackupDocument(exportedAt, conversations)
    }

    fun toCard(item: ConversationBackupItem, existing: AgentCard?): AgentCard {
        val base = existing ?: AgentCard(
            id = item.id,
            name = item.name,
            icon = "codex",
            recipeId = item.recipeId,
            templateId = "tpl_codex_ubuntu",
            profileId = item.profileId,
            modelId = item.modelId,
            termuxSessionName = "agentdeck-" + item.id,
            workspaceNamespace = PathNamespace.UBUNTU,
            workspacePath = item.workspacePath,
        )
        return base.copy(
            name = item.name,
            customTitle = item.customTitle,
            recipeId = item.recipeId,
            profileId = item.profileId,
            modelId = item.modelId,
            permissionLevel = item.permissionLevel?.let { raw ->
                runCatching { CodexPermissionLevel.valueOf(raw) }.getOrNull()
            },
            workspacePath = item.workspacePath,
            pinned = item.pinned,
            archived = item.archived,
            identity = item.identity,
        )
    }
}

data class ConversationBackupPreview(
    val conversationCount: Int,
    val identityCount: Int,
    val names: List<String>,
)
