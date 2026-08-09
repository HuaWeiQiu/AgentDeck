package com.agentdeck.app.domain.chat

import com.agentdeck.app.domain.model.CodexPermissionLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CodexProtocolTest {
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
            ),
            items.map { it.kind },
        )
        assertEquals("/root", items.last().detail)
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
}
