package com.agentdeck.app.data.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class ProviderCredentialVaultTest {
    @Test
    fun `credentials round trip as encrypted blobs and can be deleted`() {
        val store = MemoryBlobStore()
        val vault = EncryptedProviderCredentialVault(JvmGcmCipher(), store)
        val secret = "sub2api-test-key".toByteArray()

        vault.save("credential_1", secret)

        assertTrue(vault.contains("credential_1"))
        assertFalse(store.values.getValue("credential_1").ciphertext.contentEquals(secret))
        assertArrayEquals(secret, vault.load("credential_1"))
        vault.delete("credential_1")
        assertNull(vault.load("credential_1"))
    }

    @Test
    fun `aad prevents swapping encrypted credentials between refs`() {
        val store = MemoryBlobStore()
        val vault = EncryptedProviderCredentialVault(JvmGcmCipher(), store)
        vault.save("credential_a", "key-a".toByteArray())
        store.values["credential_b"] = store.values.getValue("credential_a")

        val result = runCatching { vault.load("credential_b") }

        assertTrue(result.exceptionOrNull() is CredentialVaultException)
    }

    @Test
    fun `invalid refs and multiline secrets fail closed`() {
        val vault = EncryptedProviderCredentialVault(JvmGcmCipher(), MemoryBlobStore())

        assertTrue(runCatching { vault.save("../escape", "key".toByteArray()) }.isFailure)
        assertTrue(runCatching { vault.save("valid", "key\nleak".toByteArray()) }.isFailure)
    }

    @Test
    fun `invalid decrypted secret fails closed`() {
        val cipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray) =
                EncryptedCredential(ByteArray(12), plaintext.copyOf())

            override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray) =
                "key\nleak".toByteArray()
        }
        val vault = EncryptedProviderCredentialVault(cipher, MemoryBlobStore())
        vault.save("credential_1", "valid-key".toByteArray())

        assertTrue(runCatching { vault.load("credential_1") }.exceptionOrNull() is CredentialVaultException)
    }

    @Test
    fun `undecryptable credential is reported as invalidated and purged`() {
        val store = MemoryBlobStore()
        val writer = EncryptedProviderCredentialVault(JvmGcmCipher(), store)
        writer.save("credential_1", "valid-key".toByteArray())
        val brokenKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val random = SecureRandom()
        val keystoreLostCipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential {
                val iv = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, brokenKey, GCMParameterSpec(128, iv))
                return EncryptedCredential(iv, cipher.doFinal(plaintext))
            }

            override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray =
                throw AEADBadTagException("keystore key was invalidated")
        }
        val vault = EncryptedProviderCredentialVault(keystoreLostCipher, store)

        val error = runCatching { vault.load("credential_1") }.exceptionOrNull()

        assertTrue(error is CredentialInvalidatedException)
        assertTrue((error as CredentialInvalidatedException).cause is AEADBadTagException)
        assertFalse(store.contains("credential_1"))
        assertNull(vault.load("credential_1"))
    }
}

private class MemoryBlobStore : CredentialBlobStore {
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
}

private class JvmGcmCipher : CredentialCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return EncryptedCredential(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }
}
