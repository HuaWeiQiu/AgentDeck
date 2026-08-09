package com.agentdeck.app.data.secure

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProviderCredentialBrokerTest {
    @Test
    fun `only authorized loopback request receives credential`() {
        val vault = MemoryVault("cred_test", "sk-secret".toByteArray())
        ProviderCredentialBroker(vault, "cred_test").use { broker ->
            val token = "a".repeat(64)
            broker.authorize(token)

            assertFalse(request(broker.port, "b".repeat(64), "cred_test").getBoolean("ok"))
            val response = request(broker.port, token, "cred_test")

            assertTrue(response.getBoolean("ok"))
            assertEquals(
                "sk-secret",
                String(
                    Base64.getDecoder().decode(response.getString("api_key_b64")),
                    StandardCharsets.UTF_8,
                ),
            )
        }
    }

    @Test
    fun `wrong credential reference is rejected`() {
        val vault = MemoryVault("cred_test", "sk-secret".toByteArray())
        ProviderCredentialBroker(vault, "cred_test").use { broker ->
            val token = "c".repeat(64)
            broker.authorize(token)

            assertFalse(request(broker.port, token, "cred_other").getBoolean("ok"))
        }
    }

    @Test
    fun `instance authorization cannot be replaced`() {
        val vault = MemoryVault("cred_test", "sk-secret".toByteArray())
        ProviderCredentialBroker(vault, "cred_test").use { broker ->
            val original = "c".repeat(64)
            broker.authorize(original)

            assertTrue(runCatching { broker.authorize("d".repeat(64)) }.isFailure)
            assertTrue(request(broker.port, original, "cred_test").getBoolean("ok"))
        }
    }

    @Test
    fun `oversized request is rejected before JSON parsing`() {
        val vault = MemoryVault("cred_test", "sk-secret".toByteArray())
        ProviderCredentialBroker(vault, "cred_test").use { broker ->
            Socket("127.0.0.1", broker.port).use { socket ->
                val output = socket.getOutputStream().bufferedWriter()
                output.write("x".repeat(2_049))
                output.newLine()
                output.flush()

                val response = JSONObject(socket.getInputStream().bufferedReader().readLine())
                assertFalse(response.getBoolean("ok"))
            }
        }
    }

    private fun request(port: Int, token: String, credentialRef: String): JSONObject =
        Socket("127.0.0.1", port).use { socket ->
            val output = socket.getOutputStream().bufferedWriter()
            output.write(
                JSONObject()
                    .put("token", token)
                    .put("credential_ref", credentialRef)
                    .toString(),
            )
            output.newLine()
            output.flush()
            JSONObject(socket.getInputStream().bufferedReader().readLine())
        }

    private class MemoryVault(
        private val ref: String,
        initial: ByteArray,
    ) : ProviderCredentialVault {
        private var value = initial.copyOf()

        override fun save(credentialRef: String, secret: ByteArray) {
            require(credentialRef == ref)
            value.fill(0)
            value = secret.copyOf()
        }

        override fun load(credentialRef: String): ByteArray? =
            value.copyOf().takeIf { credentialRef == ref }

        override fun contains(credentialRef: String) = credentialRef == ref

        override fun delete(credentialRef: String) {
            if (credentialRef == ref) value.fill(0)
        }
    }
}
