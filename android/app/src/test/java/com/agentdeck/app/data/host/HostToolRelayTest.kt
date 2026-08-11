package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.settings.ExperienceLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 轻量级：验证 guest 请求 JSON 经 broker 的结果可序列化为 CLI 期望的响应形状。
 * 不拉起 FileObserver（依赖 Android）。
 */
class HostToolRelayTest {
    @Test
    fun `broker response shape for cli`() = runBlocking {
        val store = InMemoryWorkspaceDocumentStore()
        val broker = DefaultHostToolBroker(
            policyProvider = {
                DefaultHostToolBroker.policyFrom(ExperienceLevel.ADVANCED, true, true)
            },
            workspace = { store },
            approval = { _, _, _ -> true },
        )
        val now = 1_700_000_000_000L
        val token = broker.mintToken("c1", "i1", now)
        store.write("hi.txt", "hello".toByteArray(), 1024)
        val ok = broker.invoke(
            com.agentdeck.app.domain.host.HostToolCall(
                conversationId = "c1",
                instanceId = "i1",
                tool = "workspace.read",
                args = mapOf("path" to "hi.txt"),
                auth = token,
            ),
            nowEpochMs = now,
        )
        assertTrue(ok is HostToolResult.Ok)
        val payload = (ok as HostToolResult.Ok).payload
        assertEquals("base64", payload["encoding"])
        assertTrue(payload["bytes"]!!.isNotBlank())

        val denied = broker.invoke(
            com.agentdeck.app.domain.host.HostToolCall(
                conversationId = "c1",
                instanceId = "i1",
                tool = "workspace.read",
                args = mapOf("path" to "../x"),
                auth = token,
            ),
            nowEpochMs = now,
        )
        assertEquals("host_path_invalid", (denied as HostToolResult.Denied).code)

        // JSON shape for agentdeck-host
        val json = JSONObject()
            .put("outcome", "denied")
            .put("code", denied.code)
            .put("userMessage", denied.userMessage)
        assertTrue(json.getString("userMessage").isNotBlank())
    }

    @Test
    fun `request id pattern rejects traversal names`() {
        val dir = Files.createTempDirectory("host-req").toFile()
        try {
            val bad = File(dir, "../evil.json")
            assertTrue(!bad.name.matches(Regex("[a-f0-9]{8,64}\\.json")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
