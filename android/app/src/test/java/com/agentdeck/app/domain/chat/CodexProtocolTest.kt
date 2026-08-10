package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId
import com.agentdeck.app.domain.model.CodexPermissionLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexProtocolTest {
    @Test
    fun `turn input sends images natively and files as readable local paths`() {
        val image = ChatAttachment(
            id = "image-1",
            name = "shot.png",
            mimeType = "image/png",
            sizeBytes = 10,
            guestPath = "/root/projects/.agentdeck-attachments/chat/shot.png",
            kind = ChatAttachmentKind.IMAGE,
        )
        val file = ChatAttachment(
            id = "file-1",
            name = "notes.md",
            mimeType = "text/markdown",
            sizeBytes = 20,
            guestPath = "/root/projects/.agentdeck-attachments/chat/notes.md",
            kind = ChatAttachmentKind.FILE,
        )

        val params = CodexProtocol.turnStartParams(
            threadId = "thread-1",
            text = "检查这些内容",
            attachments = listOf(image, file),
        )
        val input = params.getJSONArray("input")

        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertTrue(input.getJSONObject(0).getString("text").contains(file.guestPath))
        assertEquals("localImage", input.getJSONObject(1).getString("type"))
        assertEquals(image.guestPath, input.getJSONObject(1).getString("path"))
    }

    @Test
    fun `image only turn still carries an explicit text prompt`() {
        val image = ChatAttachment(
            id = "image-1",
            name = "shot.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 10,
            guestPath = "/root/projects/.agentdeck-attachments/chat/shot.jpg",
            kind = ChatAttachmentKind.IMAGE,
        )

        val params = CodexProtocol.turnSteerParams("thread-1", "turn-1", "", listOf(image))

        assertEquals("请查看附加图片。", params.getJSONArray("input").getJSONObject(0).getString("text"))
    }

    @Test
    fun `turn input rejects attachment paths outside the private runtime directory`() {
        val attachment = ChatAttachment(
            id = "file-1",
            name = "passwd",
            mimeType = "text/plain",
            sizeBytes = 10,
            guestPath = "/etc/passwd",
            kind = ChatAttachmentKind.FILE,
        )

        assertThrows(IllegalArgumentException::class.java) {
            CodexProtocol.turnStartParams(
                threadId = "thread-1",
                text = "读取",
                attachments = listOf(attachment),
            )
        }
    }

    @Test
    fun `thread resume requests the newest 50 turns without embedding full history`() {
        val params = CodexProtocol.threadResumeParams("thread-1", "/root/project")

        assertTrue(params.getBoolean("excludeTurns"))
        val page = params.getJSONObject("initialTurnsPage")
        assertEquals(50, page.getInt("limit"))
        assertEquals("desc", page.getString("sortDirection"))
        assertEquals("full", page.getString("itemsView"))
    }

    @Test
    fun `older history request uses the cursor and 25 turn page`() {
        val params = CodexProtocol.threadTurnsListParams("thread-1", "older-25")

        assertEquals("thread-1", params.getString("threadId"))
        assertEquals("older-25", params.getString("cursor"))
        assertEquals(25, params.getInt("limit"))
        assertEquals("desc", params.getString("sortDirection"))
        assertEquals("full", params.getString("itemsView"))
    }

    @Test
    fun `descending history page becomes chronological items and keeps cursor`() {
        val response = JSONObject(
            """
            {
              "initialTurnsPage": {
                "data": [
                  {"id":"turn-2","status":"failed","items":[],"error":{"message":"failed"}},
                  {"id":"turn-1","status":"completed","items":[
                    {"id":"u1","type":"userMessage","content":[{"type":"text","text":"hello"}]},
                    {"id":"a1","type":"agentMessage","text":"done"}
                  ]}
                ],
                "nextCursor":"older-25"
              }
            }
            """.trimIndent(),
        )

        val page = CodexProtocol.initialHistoryPage(response)

        assertEquals(listOf("u1", "a1", "turn-error-turn-2"), page.items.map { it.id })
        assertEquals(listOf("turn-1", "turn-2"), page.items.mapNotNull { it.turnId }.distinct())
        assertEquals("older-25", page.nextCursor)
    }

    @Test
    fun `initial page exposes newest in progress turn and persisted timestamp`() {
        val response = JSONObject(
            """
            {
              "thread":{"id":"thread-1","updatedAt":1700000000},
              "initialTurnsPage":{"data":[
                {"id":"turn-2","status":"inProgress","items":[]},
                {"id":"turn-1","status":"completed","items":[]}
              ],"nextCursor":null}
            }
            """.trimIndent(),
        )

        assertEquals("turn-2", CodexProtocol.inProgressTurnId(response))
        assertEquals(1_700_000_000_000L, CodexProtocol.threadUpdatedAtEpochMs(response))
    }

    @Test
    fun `model list response becomes picker options and cursor`() {
        val response = JSONObject()
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "catalog-sol")
                            .put("model", "gpt-5.6-sol")
                            .put("displayName", "GPT-5.6-Sol")
                            .put("isDefault", true),
                    )
                    .put(
                        JSONObject()
                            .put("id", "catalog-terra")
                            .put("model", "gpt-5.6-terra")
                            .put("displayName", "GPT-5.6-Terra")
                            .put("isDefault", false),
                    ),
            )
            .put("nextCursor", "page-2")

        val page = CodexProtocol.modelPage(response)
        val params = CodexProtocol.modelListParams("page-2")

        assertEquals(listOf("gpt-5.6-sol", "gpt-5.6-terra"), page.models.map { it.id })
        assertEquals("GPT-5.6-Sol", page.models.first().displayName)
        assertTrue(page.models.first().isDefault)
        assertEquals(setOf("text", "image"), page.models.first().inputModalities)
        assertEquals("page-2", page.nextCursor)
        assertEquals("page-2", params.getString("cursor"))
        assertFalse(params.getBoolean("includeHidden"))
    }

    @Test
    fun `model list preserves explicit text only capability`() {
        val page = CodexProtocol.modelPage(
            JSONObject().put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("model", "text-only")
                        .put("inputModalities", JSONArray().put("text")),
                ),
            ),
        )

        assertEquals(setOf("text"), page.models.single().inputModalities)
    }

    @Test
    fun `runtime model and provider come from app server response`() {
        val runtime = CodexProtocol.runtime(
            JSONObject()
                .put("model", "deepseek-v4-flash")
                .put("modelProvider", "deepseek"),
        )

        assertEquals("deepseek-v4-flash", runtime.model)
        assertEquals("deepseek", runtime.provider)
    }

    @Test
    fun `thread history becomes ordered chat timeline`() {
        val response = JSONObject(
            """
            {
              "thread": {
                "id": "thread-1",
                "turns": [{
                  "id": "turn-1",
                  "status": "completed",
                  "items": [
                    {"id":"u1","type":"userMessage","content":[{"type":"text","text":"hello"}]},
                    {"id":"r1","type":"reasoning","summary":["checking"]},
                    {"id":"a1","type":"agentMessage","text":"**done**"},
                    {"id":"c1","type":"commandExecution","command":"pwd","cwd":"/root","commandActions":[],"status":"completed","aggregatedOutput":"/root"}
                  ]
                }, {
                  "id": "turn-2",
                  "status": "failed",
                  "items": [],
                  "error": {"message":"failed"}
                }]
              }
            }
            """.trimIndent(),
        )

        val items = CodexProtocol.historyItems(response)

        assertEquals("thread-1", CodexProtocol.threadId(response))
        assertEquals(
            listOf(
                ChatItemKind.USER,
                ChatItemKind.REASONING,
                ChatItemKind.ASSISTANT,
                ChatItemKind.COMMAND,
                ChatItemKind.ERROR,
            ),
            items.map { it.kind },
        )
        assertEquals(listOf("turn-1", "turn-2"), items.mapNotNull { it.turnId }.distinct())
        assertEquals("/root", items[3].detail)
    }

    @Test
    fun `latest persisted in progress turn is detected for cleanup`() {
        val response = JSONObject(
            """
            {
              "thread": {
                "id": "thread-1",
                "turns": [
                  {"id":"turn-1","status":"completed","items":[]},
                  {"id":"turn-2","status":"inProgress","items":[]}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("turn-2", CodexProtocol.inProgressTurnId(response))
        response.getJSONObject("thread").getJSONArray("turns").put(
            JSONObject("""{"id":"turn-3","status":"completed","items":[]}"""),
        )
        assertNull(CodexProtocol.inProgressTurnId(response))
    }

    @Test
    fun `persisted thread timestamp is converted to milliseconds only when conversation exists`() {
        val response = JSONObject(
            """{"thread":{"id":"thread-1","updatedAt":1700000000,"turns":[{"id":"turn-1"}]}}""",
        )
        val empty = JSONObject(
            """{"thread":{"id":"thread-2","updatedAt":1700000000,"turns":[]}}""",
        )

        assertEquals(1_700_000_000_000L, CodexProtocol.threadUpdatedAtEpochMs(response))
        assertNull(CodexProtocol.threadUpdatedAtEpochMs(empty))
    }

    @Test
    fun `server user item replaces optimistic item and agent deltas append`() {
        val optimistic = ChatItem("local-user-1", ChatItemKind.USER, "hello")
        val serverUser = ChatItem("user-1", ChatItemKind.USER, "hello")

        val replaced = CodexProtocol.upsert(listOf(optimistic), serverUser)
        val firstDelta = CodexProtocol.appendAgentDelta(replaced, "agent-1", "one")
        val secondDelta = CodexProtocol.appendAgentDelta(firstDelta, "agent-1", " two")

        assertFalse(replaced.any { it.id.startsWith("local-user-") })
        assertEquals("one two", secondDelta.last().text)
    }

    @Test
    fun `image only history remains visible in the transcript`() {
        val item = CodexProtocol.item(
            JSONObject(
                """{"id":"u1","type":"userMessage","content":[{"type":"localImage","path":"/tmp/a.png"}]}""",
            ),
        )

        assertEquals("已附加 1 张图片", item?.text)
    }

    @Test
    fun `recommended proot runtime asks before unsafe operations`() {
        val started = CodexProtocol.threadStartParams("/root/project")
        val resumed = CodexProtocol.threadResumeParams("thread-1", "/root/project")
        val turn = CodexProtocol.turnStartParams("thread-1", "hello")

        assertEquals("read-only", started.getString("sandbox"))
        assertEquals("untrusted", started.getString("approvalPolicy"))
        assertEquals("thread-1", resumed.getString("threadId"))
        assertEquals("/root/project", resumed.getString("cwd"))
        assertEquals("externalSandbox", turn.getJSONObject("sandboxPolicy").getString("type"))
        assertEquals("enabled", turn.getJSONObject("sandboxPolicy").getString("networkAccess"))
        assertEquals("untrusted", turn.getString("approvalPolicy"))
        assertEquals("hello", turn.getJSONArray("input").getJSONObject(0).getString("text"))
    }

    @Test
    fun `native thread start and resume carry profile config and managed model overrides`() {
        val config = JSONObject()
            .put("model_reasoning_effort", "high")
            .put("features", JSONObject().put("multi_agent", true))

        val started = CodexProtocol.threadStartParams(
            cwd = "/root/project",
            profileConfig = config,
            modelOverride = "bound-model",
            modelProviderOverride = "agentdeck_provider",
        )
        val resumed = CodexProtocol.threadResumeParams(
            threadId = "thread-1",
            cwd = "/root/project",
            profileConfig = config,
            modelOverride = "bound-model",
            modelProviderOverride = "agentdeck_provider",
        )

        assertEquals("high", started.getJSONObject("config").getString("model_reasoning_effort"))
        assertTrue(
            started.getJSONObject("config").getJSONObject("features").getBoolean("multi_agent"),
        )
        assertEquals("bound-model", started.getString("model"))
        assertEquals("agentdeck_provider", started.getString("modelProvider"))
        assertEquals("thread-1", resumed.getString("threadId"))
        assertEquals("bound-model", resumed.getString("model"))
    }

    @Test
    fun `developer instructions use the native thread field without mutating profile config`() {
        val config = JSONObject()
            .put("developer_instructions", "You are 夜不修.")
            .put("model_reasoning_effort", "high")

        val started = CodexProtocol.threadStartParams(
            cwd = "/root/project",
            profileConfig = config,
        )
        val resumed = CodexProtocol.threadResumeParams(
            threadId = "thread-1",
            cwd = "/root/project",
            profileConfig = config,
        )

        assertEquals("You are 夜不修.", started.getString("developerInstructions"))
        assertEquals("You are 夜不修.", resumed.getString("developerInstructions"))
        assertFalse(started.getJSONObject("config").has("developer_instructions"))
        assertFalse(resumed.getJSONObject("config").has("developer_instructions"))
        assertEquals("You are 夜不修.", config.getString("developer_instructions"))
    }

    @Test
    fun `thread resume clears removed developer instructions`() {
        val started = CodexProtocol.threadStartParams(
            cwd = "/root/project",
            profileConfig = JSONObject(),
        )
        val resumed = CodexProtocol.threadResumeParams(
            threadId = "thread-1",
            cwd = "/root/project",
            profileConfig = JSONObject(),
        )

        assertFalse(started.has("developerInstructions"))
        assertEquals("", resumed.getString("developerInstructions"))
    }

    @Test
    fun `turn carries persistent identity in collaboration developer instructions`() {
        val params = CodexProtocol.turnStartParams(
            threadId = "thread-1",
            text = "你是谁？",
            collaborationModel = "deepseek-v4-flash",
            reasoningEffort = "max",
            developerInstructions = "You are 夜不修.",
        )

        val mode = params.getJSONObject("collaborationMode")
        val settings = mode.getJSONObject("settings")
        assertEquals("default", mode.getString("mode"))
        assertEquals("deepseek-v4-flash", settings.getString("model"))
        assertEquals("max", settings.getString("reasoning_effort"))
        assertEquals("You are 夜不修.", settings.getString("developer_instructions"))
    }

    @Test
    fun `permission levels map to enforced app server policies`() {
        val readOnly = CodexProtocol.turnStartParams(
            "thread-1",
            "inspect",
            CodexPermissionLevel.READ_ONLY,
        )
        val askFirst = CodexProtocol.turnStartParams(
            "thread-1",
            "edit",
            CodexPermissionLevel.ASK_FIRST,
        )
        val fullAccess = CodexProtocol.turnStartParams(
            "thread-1",
            "edit",
            CodexPermissionLevel.FULL_ACCESS,
        )

        assertEquals("untrusted", readOnly.getString("approvalPolicy"))
        assertEquals("untrusted", askFirst.getString("approvalPolicy"))
        assertEquals("never", fullAccess.getString("approvalPolicy"))
        assertEquals("externalSandbox", readOnly.getJSONObject("sandboxPolicy").getString("type"))
        assertEquals(true, CodexProtocol.shouldAutoDecline(CodexPermissionLevel.READ_ONLY))
        assertEquals(false, CodexProtocol.shouldAutoDecline(CodexPermissionLevel.ASK_FIRST))
        assertEquals(false, CodexProtocol.shouldAutoDecline(CodexPermissionLevel.FULL_ACCESS))
    }

    @Test
    fun `permission approvals grant requested profile or an empty profile`() {
        val requested = JSONObject()
            .put("network", JSONObject().put("enabled", true))
            .toString()
        val approval = ChatApproval(
            requestId = com.agentdeck.app.data.chat.RpcRequestId.Text("approval-1"),
            kind = ApprovalKind.PERMISSIONS,
            title = "permissions",
            detail = "network",
            requestedPermissions = requested,
        )

        val accepted = CodexProtocol.approvalResponse(approval, "acceptForSession")
        val declined = CodexProtocol.approvalResponse(approval, "decline")

        assertEquals("session", accepted.getString("scope"))
        assertEquals(true, accepted.getJSONObject("permissions").getJSONObject("network").getBoolean("enabled"))
        assertEquals(0, declined.getJSONObject("permissions").length())
        assertNull(declined.opt("decision"))
    }

    @Test
    fun `steer params carry thread turn and text input`() {
        val params = CodexProtocol.turnSteerParams("thread-1", "turn-9", "补充一下")
        assertEquals("thread-1", params.getString("threadId"))
        assertEquals("turn-9", params.getString("expectedTurnId"))
        val input = params.getJSONArray("input").getJSONObject(0)
        assertEquals("text", input.getString("type"))
        assertEquals("补充一下", input.getString("text"))
    }

    @Test
    fun `requestUserInput params parse questions options and flags`() {
        val params = JSONObject()
            .put("itemId", "item-1")
            .put("threadId", "thread-1")
            .put("turnId", "turn-1")
            .put("isBlocking", true)
            .put(
                "questions",
                JSONArray().put(
                    JSONObject()
                        .put("id", "q1")
                        .put("header", "环境")
                        .put("question", "部署到哪个环境？")
                        .put("isOther", true)
                        .put(
                            "options",
                            JSONArray().put(
                                JSONObject().put("label", "staging").put("description", "预发"),
                            ),
                        ),
                ).put(
                    JSONObject()
                        .put("id", "q2")
                        .put("header", "密钥")
                        .put("question", "提供 API Key")
                        .put("isSecret", true),
                ),
            )

        val request = CodexProtocol.parseUserInputRequest(RpcRequestId.Number(7), params)

        assertTrue(request != null)
        request!!
        assertEquals("item-1", request.itemId)
        assertEquals(2, request.questions.size)
        val first = request.questions[0]
        assertEquals("q1", first.id)
        assertEquals("环境", first.header)
        assertTrue(first.isOther)
        assertEquals(1, first.options.size)
        assertEquals("staging", first.options[0].label)
        assertTrue(request.questions[1].isSecret)
        assertTrue(request.questions[1].options.isEmpty())
    }

    @Test
    fun `requestUserInput without questions is rejected`() {
        val params = JSONObject().put("itemId", "item-1").put("questions", JSONArray())
        assertNull(CodexProtocol.parseUserInputRequest(RpcRequestId.Number(7), params))
    }

    @Test
    fun `userInput response answers every question with schema shape`() {
        val request = ChatUserInputRequest(
            requestId = RpcRequestId.Number(3),
            itemId = "item-1",
            questions = listOf(
                ToolUserInputQuestion(id = "q1", header = "h", question = "q"),
                ToolUserInputQuestion(id = "q2", header = "h", question = "q"),
            ),
        )

        val response = CodexProtocol.userInputResponse(request, mapOf("q1" to listOf("staging")))
        val answers = response.getJSONObject("answers")
        assertEquals("staging", answers.getJSONObject("q1").getJSONArray("answers").getString(0))
        assertEquals(0, answers.getJSONObject("q2").getJSONArray("answers").length())
    }

    @Test
    fun `fileChange item parses update add and delete patches`() {
        val item = JSONObject()
            .put("id", "fc-1")
            .put("type", "fileChange")
            .put("status", "completed")
            .put(
                "changes",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "update")
                            .put("path", "src/Main.kt")
                            .put("unified_diff", "@@ -1 +1 @@\n-old\n+new"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "add")
                            .put("path", "src/New.kt")
                            .put("content", "val x = 1"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "delete")
                            .put("path", "src/Old.kt")
                            .put("content", "val gone = true"),
                    ),
            )

        val parsed = CodexProtocol.item(item)!!
        assertEquals(3, parsed.patches.size)
        assertEquals("src/Main.kt", parsed.patches[0].path)
        assertTrue(parsed.patches[0].diff.contains("+new"))
        assertEquals("+val x = 1", parsed.patches[1].diff)
        assertEquals("-val gone = true", parsed.patches[2].diff)
        assertTrue(parsed.text.contains("src/Main.kt"))
    }

    @Test
    fun `patchUpdated notification merges new file patches`() {
        val params = JSONObject()
            .put("itemId", "fc-1")
            .put("threadId", "thread-1")
            .put("turnId", "turn-1")
            .put(
                "changes",
                JSONArray().put(
                    JSONObject()
                        .put("path", "src/Extra.kt")
                        .put("kind", JSONObject().put("type", "update"))
                        .put("diff", "+added"),
                ),
            )

        val patches = CodexProtocol.patchUpdatedPatches(params)
        assertEquals(1, patches.size)
        assertEquals("src/Extra.kt", patches[0].path)
        assertEquals("update", patches[0].kind)
        assertEquals("+added", patches[0].diff)
    }

    @Test
    fun `upsert keeps live patches when snapshot carries none`() {
        val withPatch = ChatItem(
            id = "fc-1",
            kind = ChatItemKind.FILE_CHANGE,
            text = "src/Main.kt",
            patches = listOf(FilePatch("src/Main.kt", "update", "+new")),
        )
        val snapshot = withPatch.copy(patches = emptyList(), status = "completed")

        val merged = CodexProtocol.upsert(listOf(withPatch), snapshot)
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].patches.size)
        assertEquals("completed", merged[0].status)
    }
}
