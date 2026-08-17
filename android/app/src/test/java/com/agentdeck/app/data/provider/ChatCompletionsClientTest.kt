package com.agentdeck.app.data.provider

import com.agentdeck.app.data.secure.ProviderCredentialVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCompletionsClientTest {
    @Test
    fun `parseDelta reads openai stream chunk`() {
        val method = ChatCompletionsClient::class.java.getDeclaredMethod(
            "parseDelta",
            String::class.java,
        )
        method.isAccessible = true
        val client = ChatCompletionsClient(
            vault = object : ProviderCredentialVault {
                override fun contains(credentialRef: String) = false
                override fun load(credentialRef: String) = null
                override fun save(credentialRef: String, secret: ByteArray) = Unit
                override fun delete(credentialRef: String) = Unit
            },
        )
        val delta = method.invoke(
            client,
            """{"choices":[{"delta":{"content":"hi"}}]}""",
        ) as String?
        assertEquals("hi", delta)
        assertNull(method.invoke(client, """{"choices":[]}""") as String?)
    }
}
