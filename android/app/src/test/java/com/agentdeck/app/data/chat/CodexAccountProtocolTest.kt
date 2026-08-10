package com.agentdeck.app.data.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAccountProtocolTest {
    @Test
    fun `account read distinguishes ChatGPT API key and signed out`() {
        val chatGpt = CodexAccountProtocol.parseAccountRead(
            JSONObject(
                """{"account":{"type":"chatgpt","email":"a@example.com","planType":"plus"},"requiresOpenaiAuth":true}""",
            ),
        )
        val apiKey = CodexAccountProtocol.parseAccountRead(
            JSONObject("""{"account":{"type":"apiKey"},"requiresOpenaiAuth":true}"""),
        )
        val signedOut = CodexAccountProtocol.parseAccountRead(
            JSONObject("""{"account":null,"requiresOpenaiAuth":false}"""),
        )

        assertEquals(CodexAccountType.CHATGPT, chatGpt.account?.type)
        assertEquals("a@example.com", chatGpt.account?.email)
        assertEquals("plus", chatGpt.account?.planType)
        assertEquals(CodexAccountType.API_KEY, apiKey.account?.type)
        assertNull(signedOut.account)
        assertFalse(signedOut.requiresOpenAiAuth)
    }

    @Test
    fun `device login requires HTTPS and matches completion by login id`() {
        val login = CodexAccountProtocol.parseDeviceLogin(
            JSONObject(
                """{"type":"chatgptDeviceCode","loginId":"login-1","verificationUrl":"https://auth.openai.com/codex/device","userCode":"ABCD-1234"}""",
            ),
        )
        val ignored = CodexAccountProtocol.parseLoginCompletion(
            JSONObject("""{"loginId":"login-2","success":true}"""),
            login.loginId,
        )
        val completed = CodexAccountProtocol.parseLoginCompletion(
            JSONObject("""{"loginId":"login-1","success":false,"error":"expired"}"""),
            login.loginId,
        )

        assertEquals("ABCD-1234", login.userCode)
        assertNull(ignored)
        assertFalse(completed?.success ?: true)
        assertEquals("expired", completed?.error)
        assertTrue(
            runCatching {
                CodexAccountProtocol.parseDeviceLogin(
                    JSONObject()
                        .put("type", "chatgptDeviceCode")
                        .put("loginId", "login-1")
                        .put("verificationUrl", "https:///missing-host")
                        .put("userCode", "ABCD-1234"),
                )
            }.isFailure,
        )
    }

    @Test
    fun `login params use official account methods shape`() {
        val api = CodexAccountProtocol.apiKeyLoginParams("secret")
        val device = CodexAccountProtocol.deviceCodeLoginParams()

        assertEquals("apiKey", api.getString("type"))
        assertEquals("secret", api.getString("apiKey"))
        assertEquals("chatgptDeviceCode", device.getString("type"))
        assertTrue(CodexAccountProtocol.accountReadParams().has("refreshToken"))
    }
}
