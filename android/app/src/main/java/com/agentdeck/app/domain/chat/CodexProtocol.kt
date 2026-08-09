package com.agentdeck.app.domain.chat

import org.json.JSONArray
import org.json.JSONObject
import com.agentdeck.app.domain.model.CodexPermissionLevel

object CodexProtocol {
    fun threadStartParams(
        cwd: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
    ): JSONObject = runtimeThreadParams(cwd, permissionLevel)

    fun threadResumeParams(
        threadId: String,
        cwd: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
    ): JSONObject = runtimeThreadParams(cwd, permissionLevel).put("threadId", threadId)

    fun turnStartParams(
        threadId: String,
        text: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
    ): JSONObject =
        JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("approvalPolicy", permissionLevel.approvalPolicy)
            .put(
                "sandboxPolicy",
                JSONObject()
                    .put("type", "externalSandbox")
                    .put("networkAccess", "enabled"),
            )

    fun shouldAutoDecline(permissionLevel: CodexPermissionLevel): Boolean =
        permissionLevel == CodexPermissionLevel.READ_ONLY

    fun approvalResponse(approval: ChatApproval, decision: String): JSONObject {
        if (approval.kind != ApprovalKind.PERMISSIONS) {
            return JSONObject().put("decision", decision)
        }
        val permissions = if (decision == "accept" || decision == "acceptForSession") {
            approval.requestedPermissions?.let(::JSONObject) ?: JSONObject()
        } else {
            JSONObject()
        }
        return JSONObject()
            .put("permissions", permissions)
            .put("scope", if (decision == "acceptForSession") "session" else "turn")
    }

    fun threadId(response: JSONObject): String =
        requireNotNull(response.optJSONObject("thread")?.nullableString("id")) {
            "Codex 响应缺少 thread id"
        }

    fun turnId(response: JSONObject): String =
        requireNotNull(response.optJSONObject("turn")?.nullableString("id")) {
            "Codex 响应缺少 turn id"
        }

    fun runtime(response: JSONObject): CodexRuntime = CodexRuntime(
        model = requireNotNull(response.nullableString("model")) {
            "Codex app-server 未返回实际模型"
        },
        provider = requireNotNull(response.nullableString("modelProvider")) {
            "Codex app-server 未返回实际 Provider"
        },
    )

    fun inProgressTurnId(response: JSONObject): String? {
        val turns = response.optJSONObject("thread")?.optJSONArray("turns") ?: return null
        val turn = turns.optJSONObject(turns.length() - 1) ?: return null
        return turn.nullableString("id").takeIf { turn.optString("status") == "inProgress" }
    }

    fun historyItems(response: JSONObject): List<ChatItem> {
        val turns = response.optJSONObject("thread")?.optJSONArray("turns") ?: return emptyList()
        return buildList {
            turns.objects().forEach { turn ->
                turn.optJSONArray("items")?.objects()?.mapNotNull(::item)?.let(::addAll)
                turn.optJSONObject("error")?.let { error ->
                    val message = error.nullableString("message") ?: error.toString()
                    add(ChatItem("turn-error-${turn.optString("id")}", ChatItemKind.ERROR, message))
                }
            }
        }
    }

    fun turnItems(turn: JSONObject): List<ChatItem> =
        turn.optJSONArray("items")?.objects()?.mapNotNull(::item).orEmpty()

    fun item(value: JSONObject): ChatItem? {
        val id = value.nullableString("id") ?: return null
        return when (val type = value.optString("type")) {
            "userMessage" -> ChatItem(
                id,
                ChatItemKind.USER,
                value.optJSONArray("content")
                    ?.objects()
                    ?.filter { it.optString("type") == "text" }
                    ?.mapNotNull { it.nullableString("text") }
                    ?.joinToString("\n")
                    .orEmpty(),
            )

            "agentMessage" -> ChatItem(
                id,
                ChatItemKind.ASSISTANT,
                value.nullableString("text").orEmpty(),
            )

            "reasoning" -> {
                val summary = value.optJSONArray("summary").strings()
                val content = value.optJSONArray("content").strings()
                ChatItem(
                    id,
                    ChatItemKind.REASONING,
                    (summary.ifEmpty { content }).joinToString("\n").ifBlank { "正在思考" },
                )
            }

            "commandExecution" -> ChatItem(
                id,
                ChatItemKind.COMMAND,
                value.nullableString("command") ?: "运行命令",
                detail = value.nullableString("aggregatedOutput")?.takeLast(MAX_DETAIL_LENGTH),
                status = value.nullableString("status"),
            )

            "fileChange" -> {
                val changes = value.optJSONArray("changes")?.objects().orEmpty()
                val paths = changes.mapNotNull { it.nullableString("path") }.distinct()
                ChatItem(
                    id,
                    ChatItemKind.FILE_CHANGE,
                    when {
                        paths.isNotEmpty() -> paths.joinToString("、", limit = 3)
                        changes.isNotEmpty() -> "修改 ${changes.size} 个文件"
                        else -> "准备文件修改"
                    },
                    status = value.nullableString("status"),
                )
            }

            "mcpToolCall" -> ChatItem(
                id,
                ChatItemKind.TOOL,
                listOfNotNull(value.nullableString("server"), value.nullableString("tool"))
                    .joinToString(" / ")
                    .ifBlank { "MCP 工具" },
                status = value.nullableString("status"),
            )

            "dynamicToolCall", "collabAgentToolCall", "subAgentActivity" -> ChatItem(
                id,
                ChatItemKind.TOOL,
                value.nullableString("tool") ?: type,
                status = value.nullableString("status"),
            )

            "webSearch" -> ChatItem(
                id,
                ChatItemKind.TOOL,
                value.nullableString("query") ?: "搜索网页",
                status = "webSearch",
            )

            "plan" -> ChatItem(
                id,
                ChatItemKind.REASONING,
                value.nullableString("text") ?: "更新计划",
            )

            "imageView", "imageGeneration" -> ChatItem(
                id,
                ChatItemKind.TOOL,
                value.nullableString("path") ?: value.nullableString("savedPath") ?: "图像",
                status = value.nullableString("status"),
            )

            else -> ChatItem(id, ChatItemKind.TOOL, type.ifBlank { "Codex 活动" })
        }
    }

    fun upsert(current: List<ChatItem>, incoming: ChatItem): List<ChatItem> {
        val existingIndex = current.indexOfFirst { it.id == incoming.id }
        if (existingIndex >= 0) {
            return current.toMutableList().apply { set(existingIndex, incoming) }
        }
        if (incoming.kind == ChatItemKind.USER) {
            val optimisticIndex = current.indexOfFirst {
                it.id.startsWith("local-user-") && it.kind == ChatItemKind.USER && it.text == incoming.text
            }
            if (optimisticIndex >= 0) {
                return current.toMutableList().apply { set(optimisticIndex, incoming) }
            }
        }
        return current + incoming
    }

    fun appendAgentDelta(current: List<ChatItem>, itemId: String, delta: String): List<ChatItem> {
        val index = current.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return current + ChatItem(itemId, ChatItemKind.ASSISTANT, delta)
        }
        val item = current[index]
        return current.toMutableList().apply {
            set(index, item.copy(kind = ChatItemKind.ASSISTANT, text = item.text + delta))
        }
    }

    fun errorMessage(params: JSONObject): String {
        val nested = params.optJSONObject("error")
        return nested?.nullableString("message")
            ?: params.nullableString("message")
            ?: "Codex 返回了错误"
    }

    private fun runtimeThreadParams(
        cwd: String,
        permissionLevel: CodexPermissionLevel,
    ): JSONObject =
        JSONObject()
            .put("cwd", cwd)
            .put("approvalPolicy", permissionLevel.approvalPolicy)
            // Keep thread bootstrap read-only so opening a chat does not mark the project
            // trusted. Every executable turn atomically overrides this with externalSandbox.
            .put("sandbox", "read-only")

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        if (this@objects == null) return@buildList
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }

    private fun JSONArray?.strings(): List<String> = buildList {
        if (this@strings == null) return@buildList
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.nullableString(key: String): String? {
        val value = opt(key)?.takeUnless { it == JSONObject.NULL } ?: return null
        return (value as? String)?.takeIf(String::isNotBlank)
    }

    private const val MAX_DETAIL_LENGTH = 8_000
}
