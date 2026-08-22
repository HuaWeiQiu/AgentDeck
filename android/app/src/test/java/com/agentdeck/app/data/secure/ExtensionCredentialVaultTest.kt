package com.agentdeck.app.data.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class ExtensionCredentialVaultTest {
    @Test
    fun `extension credentials use validated refs and separate aad`() {
        val store = ExtensionMemoryStore()
        val vault = EncryptedExtensionCredentialVault(ExtensionTestCipher(), store)
        val ref = "extcred_0123456789abcdef0123456789abcdef"
        val secret = "mcp-token".toByteArray()

        vault.save(ref, secret)

        assertTrue(vault.contains(ref))
        assertFalse(store.values.getValue(ref).ciphertext.contentEquals(secret))
        assertArrayEquals(secret, vault.load(ref))
        vault.delete(ref)
        assertNull(vault.load(ref))
    }

    @Test
    fun `invalid refs multiline values and swapped blobs fail closed`() {
        val store = ExtensionMemoryStore()
        val vault = EncryptedExtensionCredentialVault(ExtensionTestCipher(), store)
        val first = "extcred_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val second = "extcred_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        assertTrue(runCatching { vault.save("../token", "value".toByteArray()) }.isFailure)
        assertTrue(runCatching { vault.save(first, "bad\nvalue".toByteArray()) }.isFailure)
        vault.save(first, "value".toByteArray())
        store.values[second] = store.values.getValue(first)
        assertTrue(runCatching { vault.load(second) }.isFailure)
    }

    @Test
    fun `decrypted header injection is rejected`() {
        val store = ExtensionMemoryStore()
        val ref = "extcred_cccccccccccccccccccccccccccccccc"
        val cipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray) =
                EncryptedCredential(ByteArray(12), plaintext.copyOf())

            override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray) =
                "value\r\nInjected: yes".toByteArray()
        }
        val vault = EncryptedExtensionCredentialVault(cipher, store)
        vault.save(ref, "value".toByteArray())

        assertTrue(runCatching { vault.load(ref) }.isFailure)
    }

    @Test
    fun `reconciliation prunes only unreferenced extension credentials`() {
        val store = ExtensionMemoryStore()
        val vault = EncryptedExtensionCredentialVault(ExtensionTestCipher(), store)
        val kept = "extcred_dddddddddddddddddddddddddddddddd"
        val orphan = "extcred_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        vault.save(kept, "kept".toByteArray())
        vault.save(orphan, "orphan".toByteArray())

        vault.pruneExcept(setOf(kept))

        assertTrue(vault.contains(kept))
        assertFalse(vault.contains(orphan))
    }

    @Test
    fun `undecryptable extension credential is reported as invalidated and purged`() {
        val store = ExtensionMemoryStore()
        val ref = "extcred_99999999999999999999999999999999"
        val writer = EncryptedExtensionCredentialVault(ExtensionTestCipher(), store)
        writer.save(ref, "mcp-token".toByteArray())
        val keystoreLostCipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray) =
                EncryptedCredential(ByteArray(12), plaintext.copyOf())

            override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray =
                throw AEADBadTagException("keystore key was invalidated")
        }
        val vault = EncryptedExtensionCredentialVault(keystoreLostCipher, store)

        val error = runCatching { vault.load(ref) }.exceptionOrNull()

        assertTrue(error is CredentialInvalidatedException)
        assertTrue((error as CredentialInvalidatedException).cause is AEADBadTagException)
        assertFalse(store.contains(ref))
        assertNull(vault.load(ref))
    }
}

private class ExtensionMemoryStore : CredentialBlobStore, PrunableCredentialBlobStore {
    val values = mutableMapOf<String, EncryptedCredential>()

    override fun write(credentialRef: String, encrypted: EncryptedCredential) {
        values[credentialRef] = encrypted.copy(
            iv = encrypted.iv.copyOf(),
            ciphertext = encrypted.ciphertext.copyOf(),
        )
    }

    override fun read(credentialRef: String): EncryptedCredential? = values[credentialRef]?.let {
        it.copy(iv = it.iv.copyOf(), ciphertext = it.ciphertext.copyOf())
    }

    override fun contains(credentialRef: String) = credentialRef in values

    override fun delete(credentialRef: String) {
        values.remove(credentialRef)
    }

    override fun pruneExcept(validCredentialRefs: Set<String>) {
        values.keys.retainAll(validCredentialRefs)
    }
}

private class ExtensionTestCipher : CredentialCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential = EncryptedCredential(
        iv = ByteArray(12),
        ciphertext = plaintext + aad,
    )

    override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray {
        require(encrypted.ciphertext.size >= aad.size)
        val suffix = encrypted.ciphertext.copyOfRange(encrypted.ciphertext.size - aad.size, encrypted.ciphertext.size)
        require(suffix.contentEquals(aad))
        return encrypted.ciphertext.copyOfRange(0, encrypted.ciphertext.size - aad.size)
    }
}
