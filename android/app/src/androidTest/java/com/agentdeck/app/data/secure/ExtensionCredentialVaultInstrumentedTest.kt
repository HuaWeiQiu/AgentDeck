package com.agentdeck.app.data.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExtensionCredentialVaultInstrumentedTest {
    @Test
    fun extensionBearerUsesKeystoreCiphertextAndDeletesCleanly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val vault = AndroidExtensionCredentialVault(context)
        val ref = "extcred_1234567890abcdef1234567890abcdef"
        val secret = "instrumented-private-token".toByteArray()
        val file = File(context.noBackupFilesDir, "extension_credentials/$ref.bin")
        vault.delete(ref)
        try {
            vault.save(ref, secret)

            assertTrue(vault.contains(ref))
            val loaded = requireNotNull(vault.load(ref))
            try {
                assertArrayEquals(secret, loaded)
            } finally {
                loaded.fill(0)
            }
            assertTrue(file.isFile)
            val encrypted = file.readBytes()
            try {
                assertFalse(encrypted.containsSequence(secret))
            } finally {
                encrypted.fill(0)
            }
        } finally {
            vault.delete(ref)
            secret.fill(0)
        }
        assertFalse(vault.contains(ref))
    }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.isEmpty() || sequence.size > size) return false
    return indices.take(size - sequence.size + 1).any { offset ->
        sequence.indices.all { index -> this[offset + index] == sequence[index] }
    }
}
