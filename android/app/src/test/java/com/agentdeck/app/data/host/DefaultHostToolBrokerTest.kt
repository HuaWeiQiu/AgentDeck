package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.HostApprovalGateway
import com.agentdeck.app.domain.host.HostToolCall
import com.agentdeck.app.domain.host.HostToolName
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.settings.ExperienceLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHostToolBrokerTest {
    private val store = InMemoryWorkspaceDocumentStore()
    private var experience = ExperienceLevel.ADVANCED
    private var workspaceEnabled = true
    private var hasGrant = true
    private var approveWrites = true

    private val broker = DefaultHostToolBroker(
        policyProvider = {
            DefaultHostToolBroker.policyFrom(experience, workspaceEnabled, hasGrant)
        },
        workspace = { if (hasGrant) store else null },
        approval = HostApprovalGateway { _, _, _ -> approveWrites },
    )

    private val now = 1_700_000_000_000L

    private fun call(
        tool: String,
        args: Map<String, String> = emptyMap(),
        conversationId: String = "conv-1",
        instanceId: String = "inst-1",
    ): HostToolCall {
        val token = broker.mintToken(conversationId, instanceId, now)
        return HostToolCall(conversationId, instanceId, tool, args, token)
    }

    private suspend fun invoke(call: HostToolCall): HostToolResult =
        broker.invoke(call, nowEpochMs = now)

    @Test
    fun `fail closed without auth and without grant`() = runBlocking {
        val forged = call("workspace.stat", mapOf("path" to "")).copy(
            auth = broker.mintToken("other", "inst-1", now),
        )
        val mismatch = invoke(forged)
        assertTrue(mismatch is HostToolResult.Denied)

        hasGrant = false
        val noGrant = invoke(call("workspace.stat"))
        assertEquals("host_workspace_no_grant", (noGrant as HostToolResult.Denied).code)
    }

    @Test
    fun `standard mode cannot use workspace even with grant`() = runBlocking {
        experience = ExperienceLevel.STANDARD
        val result = invoke(call("workspace.list", mapOf("path" to "")))
        assertEquals("host_standard_mode", (result as HostToolResult.Denied).code)
    }

    @Test
    fun `rejects path traversal`() = runBlocking {
        val result = invoke(call("workspace.read", mapOf("path" to "../secret")))
        assertEquals("host_path_invalid", (result as HostToolResult.Denied).code)
    }

    @Test
    fun `write requires approval and can succeed`() = runBlocking {
        approveWrites = false
        val denied = invoke(
            call("workspace.write", mapOf("path" to "a.txt", "content" to "hi", "encoding" to "utf8")),
        )
        assertEquals("host_write_denied", (denied as HostToolResult.Denied).code)

        approveWrites = true
        val ok = invoke(
            call("workspace.write", mapOf("path" to "a.txt", "content" to "hi", "encoding" to "utf8")),
        )
        assertTrue(ok is HostToolResult.Ok)

        val read = invoke(call("workspace.read", mapOf("path" to "a.txt"))) as HostToolResult.Ok
        assertEquals("2", read.payload["size"])
    }

    @Test
    fun `remove refuses directories and deletes files`() = runBlocking {
        val mkdir = invoke(call("workspace.mkdir", mapOf("path" to "dir")))
        assertTrue(mkdir is HostToolResult.Ok)
        val removeDir = invoke(call("workspace.remove", mapOf("path" to "dir")))
        assertTrue(removeDir is HostToolResult.Error)

        val written = invoke(call("workspace.write", mapOf("path" to "f.txt", "content" to "x", "encoding" to "utf8")))
        assertTrue(written is HostToolResult.Ok)
        val removed = invoke(call("workspace.remove", mapOf("path" to "f.txt")))
        assertTrue(removed is HostToolResult.Ok)
    }

    @Test
    fun `audit never stores token or body`() = runBlocking {
        invoke(call("workspace.write", mapOf("path" to "z.txt", "content" to "secret-body", "encoding" to "utf8")))
        val events = broker.recentAudit()
        assertTrue(events.isNotEmpty())
        val blob = events.joinToString { "${it.tool}:${it.code}:${it.conversationIdHash}" }
        assertTrue(!blob.contains("secret-body"))
        assertTrue(events.all { it.conversationIdHash.length == 16 })
    }

    @Test
    fun `unknown tool denied`() = runBlocking {
        val result = invoke(call("shell.exec"))
        assertEquals("host_unknown_tool", (result as HostToolResult.Denied).code)
        assertEquals(HostToolName.WORKSPACE_LIST.capability, HostToolName.WORKSPACE_LIST.capability)
    }
}
