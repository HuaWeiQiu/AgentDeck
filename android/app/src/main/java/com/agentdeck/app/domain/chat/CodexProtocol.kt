package com.agentdeck.app.domain.chat

import org.json.JSONArray
import org.json.JSONObject
import com.agentdeck.app.data.chat.RpcRequestId
import com.agentdeck.app.domain.model.CodexPermissionLevel

object CodexProtocol {
    fun modelListParams(cursor: String? = null): JSONObject = JSONObject()
        .put("limit", MODEL_LIST_PAGE_SIZE)
        .put("includeHidden", false)
        .apply { cursor?.let { put("cursor", it) } }

    fun modelPage(response: JSONObject): CodexModelPage {
        val models = response.optJSONArray("data")?.objects().orEmpty().mapNotNull { value ->
            val model = value.nullableString("model") ?: value.nullableString("id")
                ?: return@mapNotNull null
            if (model.length > MAX_MODEL_ID_LENGTH || model.any(Char::isISOControl)) {
                return@mapNotNull null
            }
            val displayName = value.nullableString("displayName")
                ?.take(MAX_MODEL_DISPLAY_NAME_LENGTH)
                ?: model
            CodexModelOption(
                id = model,
                displayName = displayName,
                isDefault = value.optBoolean("isDefault", false),
            )
        }.distinctBy { it.id }
        return CodexModelPage(
            models = models,
            nextCursor = response.nullableString("nextCursor"),
        )
    }

    fun threadStartParams(
        cwd: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
        profileConfig: JSONObject? = null,
        modelOverride: String? = null,
        modelProviderOverride: String? = null,
    ): JSONObject = runtimeThreadParams(
        cwd,
        permissionLevel,
        profileConfig,
        modelOverride,
        modelProviderOverride,
        clearMissingDeveloperInstructions = false,
    )

    fun threadResumeParams(
        threadId: String,
        cwd: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
        profileConfig: JSONObject? = null,
        modelOverride: String? = null,
        modelProviderOverride: String? = null,
    ): JSONObject = runtimeThreadParams(
        cwd,
        permissionLevel,
        profileConfig,
        modelOverride,
        modelProviderOverride,
        clearMissingDeveloperInstructions = true,
    ).put("threadId", threadId)

    fun turnStartParams(
        threadId: String,
        text: String,
        permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT,
        modelOverride: String? = null,
        collaborationModel: String? = modelOverride,
        reasoningEffort: String? = null,
        developerInstructions: String? = null,
    ): JSONObject =
        JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("approvalPolicy", permissionLevel.approvalPolicy)
            .apply {
                if (modelOverride != null) put("model", modelOverride)
                collaborationModel?.let { model ->
                    put(
                        "collaborationMode",
                        JSONObject()
                            .put("mode", "default")
                            .put(
                                "settings",
                                JSONObject()
                                    .put("model", model)
                                    .apply {
                                        reasoningEffort?.let {
                                            put("reasoning_effort", it)
                                        }
                                    }
                                    .put(
                                        "developer_instructions",
                                        developerInstructions ?: JSONObject.NULL,
                                    ),
                            ),
                    )
                }
            }
            .put(
                "sandboxPolicy",
                JSONObject()
                    .put("type", "externalSandbox")
                    .put("networkAccess", "enabled"),
            )

    /** Inject a message into an in-progress turn; `expectedTurnId` must match. */
    fun turnSteerParams(threadId: String, turnId: String, text: String): JSONObject =
        JSONObject()
            .put("threadId", threadId)
            .put("expectedTurnId", turnId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    fun parseUserInputRequest(id: RpcRequestId, params: JSONObject): ChatUserInputRequest? {
        val questionsJson = params.optJSONArray("questions") ?: return null
        val questions = buildList {
            for (index in 0 until questionsJson.length()) {
                val question = questionsJson.optJSONObject(index) ?: continue
                val questionId = question.nullableString("id") ?: continue
                add(
                    ToolUserInputQuestion(
                        id = questionId,
                        header = question.nullableString("header").orEmpty(),
                        question = question.nullableString("question").orEmpty(),
                        options = question.optJSONArray("options")
                            ?.objects()
                            ?.mapNotNull { option ->
                                val label = option.nullableString("label") ?: return@mapNotNull null
                                ToolUserInputOption(
                                    label = label,
                                    description = option.nullableString("description").orEmpty(),
                                )
                            }
                            .orEmpty(),
                        isOther = question.optBoolean("isOther", false),
                        isSecret = question.optBoolean("isSecret", false),
                    ),
                )
            }
        }
        if (questions.isEmpty()) return null
        return ChatUserInputRequest(
            requestId = id,
            itemId = params.optString("itemId"),
            questions = questions,
        )
    }

    /** `{answers: {questionId: {answers: [String]}}}` per the 0.147.0 schema. */
    fun userInputResponse(request: ChatUserInputRequest, answers: Map<String, List<String>>): JSONObject {
        val answersJson = JSONObject()
        request.questions.forEach { question ->
            answersJson.put(
                question.id,
                JSONObject().put(
                    "answers",
                    JSONArray(answers[question.id].orEmpty()),
                ),
            )
        }
        return JSONObject().put("answers", answersJson)
    }

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

    fun threadUpdatedAtEpochMs(response: JSONObject): Long? {
        val thread = response.optJSONObject("thread") ?: return null
        val turns = thread.optJSONArray("turns") ?: return null
        if (turns.length() == 0) return null
        val seconds = thread.optLong("updatedAt", 0L)
        return seconds.takeIf { it > 0L }?.let { value ->
            if (value > EPOCH_SECONDS_UPPER_BOUND) value else value * 1_000L
        }
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
                val patches = fileChangePatches(changes)
                val paths = patches.map { it.path }.distinct()
                ChatItem(
                    id,
                    ChatItemKind.FILE_CHANGE,
                    when {
                        paths.isNotEmpty() -> paths.joinToString("、", limit = 3)
                        changes.isNotEmpty() -> "修改 ${changes.size} 个文件"
                        else -> "准备文件修改"
                    },
                    status = value.nullableString("status"),
                    patches = patches,
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
            val existing = current[existingIndex]
            // Live patch updates arrive via item/fileChange/patchUpdated; keep them
            // when a later item snapshot (e.g. item/completed) carries no patches.
            val merged = if (incoming.patches.isEmpty() && existing.patches.isNotEmpty()) {
                incoming.copy(patches = existing.patches)
            } else {
                incoming
            }
            return current.toMutableList().apply { set(existingIndex, merged) }
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

    /**
     * Patches from an `item/fileChange/patchUpdated` notification:
     * `changes: [{path, kind: {type}, diff}]`.
     */
    fun patchUpdatedPatches(params: JSONObject): List<FilePatch> =
        params.optJSONArray("changes")?.objects().orEmpty().mapNotNull { change ->
            val path = change.nullableString("path") ?: return@mapNotNull null
            val diff = change.nullableString("diff").orEmpty()
            val kind = change.optJSONObject("kind")?.nullableString("type") ?: "update"
            FilePatch(path = path, kind = kind, diff = diff)
        }

    /**
     * Patches embedded in a fileChange item: `changes: [{type, path?, unified_diff?, content?}]`.
     * Add/delete changes carry full file content instead of a diff; synthesize one.
     */
    private fun fileChangePatches(changes: List<JSONObject>): List<FilePatch> =
        changes.mapNotNull { change ->
            val kind = change.nullableString("type") ?: return@mapNotNull null
            val path = change.nullableString("path").orEmpty()
            val diff = when (kind) {
                "update" -> change.nullableString("unified_diff").orEmpty()
                "add" -> synthesizeDiff(change.nullableString("content").orEmpty(), added = true)
                "delete" -> synthesizeDiff(change.nullableString("content").orEmpty(), added = false)
                else -> return@mapNotNull null
            }
            FilePatch(path = path, kind = kind, diff = diff)
        }

    private fun synthesizeDiff(content: String, added: Boolean): String =
        content.lineSequence().joinToString("\n") { line ->
            (if (added) "+" else "-") + line
        }

    private fun runtimeThreadParams(
        cwd: String,
        permissionLevel: CodexPermissionLevel,
        profileConfig: JSONObject?,
        modelOverride: String?,
        modelProviderOverride: String?,
        clearMissingDeveloperInstructions: Boolean,
    ): JSONObject {
        val config = profileConfig?.let { JSONObject(it.toString()) }
        val developerInstructions = config?.opt(DEVELOPER_INSTRUCTIONS_CONFIG_KEY)?.let { value ->
            require(value is String) { "developer_instructions 必须是字符串" }
            config.remove(DEVELOPER_INSTRUCTIONS_CONFIG_KEY)
            value
        }
        return JSONObject()
            .put("cwd", cwd)
            .put("approvalPolicy", permissionLevel.approvalPolicy)
            // Keep thread bootstrap read-only so opening a chat does not mark the project
            // trusted. Every executable turn atomically overrides this with externalSandbox.
            .put("sandbox", "read-only")
            .apply {
                if (config != null && config.length() > 0) {
                    put("config", config)
                }
                if (developerInstructions != null || clearMissingDeveloperInstructions) {
                    // Codex 0.147.0 exposes this as a direct ThreadStart/Resume field.
                    // Keeping it only inside `config` silently drops conversation identities.
                    put("developerInstructions", developerInstructions.orEmpty())
                }
                modelOverride?.let { put("model", it) }
                modelProviderOverride?.let { put("modelProvider", it) }
            }
    }

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
    private const val MODEL_LIST_PAGE_SIZE = 100
    private const val MAX_MODEL_ID_LENGTH = 160
    private const val MAX_MODEL_DISPLAY_NAME_LENGTH = 160
    private const val EPOCH_SECONDS_UPPER_BOUND = 10_000_000_000L
    private const val DEVELOPER_INSTRUCTIONS_CONFIG_KEY = "developer_instructions"
}
