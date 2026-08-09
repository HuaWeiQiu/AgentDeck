package com.agentdeck.app.data.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class ProviderCredentialVaultInstrumentedTest {
    @Test
    fun androidKeystoreCredentialRoundTripAndDelete() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val vault = AndroidProviderCredentialVault(context)
        val credentialRef = "instrumented_keystore_round_trip"
        val expected = "temporary-test-key".toByteArray(StandardCharsets.UTF_8)

        vault.delete(credentialRef)
        try {
            vault.save(credentialRef, expected)
            assertTrue(vault.contains(credentialRef))
            val actual = requireNotNull(vault.load(credentialRef))
            try {
                assertArrayEquals(expected, actual)
            } finally {
                actual.fill(0)
            }
        } finally {
            expected.fill(0)
            vault.delete(credentialRef)
        }
        assertFalse(vault.contains(credentialRef))
    }
}
