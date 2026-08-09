package com.agentdeck.app.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ProviderCredentialVault {
    fun save(credentialRef: String, secret: ByteArray)

    /** Returns a new mutable buffer. The caller must zero it immediately after use. */
    fun load(credentialRef: String): ByteArray?
    fun contains(credentialRef: String): Boolean
    fun delete(credentialRef: String)
}

class CredentialVaultException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class EncryptedCredential(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal interface CredentialCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential
    fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray
}

internal interface CredentialBlobStore {
    fun write(credentialRef: String, encrypted: EncryptedCredential)
    fun read(credentialRef: String): EncryptedCredential?
    fun contains(credentialRef: String): Boolean
    fun delete(credentialRef: String)
}

internal class EncryptedProviderCredentialVault(
    private val cipher: CredentialCipher,
    private val store: CredentialBlobStore,
) : ProviderCredentialVault {
    override fun save(credentialRef: String, secret: ByteArray) {
        validateCredentialRef(credentialRef)
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES) {
            "API Key 不能为空且不能超过 $MAX_SECRET_BYTES 字节"
        }
        require(secret.none { it == 0.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
            "API Key 包含非法字符"
        }
        val plaintext = secret.copyOf()
        try {
            store.write(credentialRef, cipher.encrypt(plaintext, aad(credentialRef)))
        } catch (error: Exception) {
            throw CredentialVaultException("无法安全保存 API Key", error)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun load(credentialRef: String): ByteArray? {
        validateCredentialRef(credentialRef)
        val encrypted = try {
            store.read(credentialRef)
        } catch (error: Exception) {
            throw CredentialVaultException("无法读取已保存的 API Key", error)
        } ?: return null
        val plaintext = try {
            cipher.decrypt(encrypted, aad(credentialRef))
        } catch (error: Exception) {
            throw CredentialVaultException("已保存的 API Key 无法解密，请重新配置", error)
        }
        return try {
                require(plaintext.isNotEmpty() && plaintext.size <= MAX_SECRET_BYTES) {
                    "已保存的 API Key 无效"
                }
                require(
                    plaintext.none {
                        it == 0.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte()
                    },
                ) { "已保存的 API Key 包含非法字符" }
                plaintext
        } catch (error: Exception) {
            plaintext.fill(0)
            throw CredentialVaultException("已保存的 API Key 无法解密，请重新配置", error)
        }
    }

    override fun contains(credentialRef: String): Boolean {
        validateCredentialRef(credentialRef)
        return store.contains(credentialRef)
    }

    override fun delete(credentialRef: String) {
        validateCredentialRef(credentialRef)
        store.delete(credentialRef)
    }

    private fun aad(credentialRef: String) = "agentdeck-provider-v1:$credentialRef".toByteArray()

    companion object {
        private const val MAX_SECRET_BYTES = 8 * 1_024

        internal fun validateCredentialRef(value: String) {
            require(value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "credentialRef 无效" }
        }
    }
}

class AndroidProviderCredentialVault(context: Context) : ProviderCredentialVault by
    EncryptedProviderCredentialVault(
        cipher = AndroidKeystoreCredentialCipher(),
        store = AndroidCredentialBlobStore(context),
    )

private class AndroidKeystoreCredentialCipher : CredentialCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(aad)
            EncryptedCredential(cipher.iv.copyOf(), cipher.doFinal(plaintext))
        }.getOrElse { error ->
            throw CredentialVaultException("Android Keystore 加密失败", error)
        }

    override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray =
        runCatching {
            require(encrypted.iv.size == GCM_IV_BYTES) { "凭据 IV 无效" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, encrypted.iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted.ciphertext)
        }.getOrElse { error ->
            throw CredentialVaultException("Android Keystore 解密失败", error)
        }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "agentdeck_provider_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}

private class AndroidCredentialBlobStore(context: Context) : CredentialBlobStore {
    private val directory = File(context.noBackupFilesDir, "provider_credentials")

    override fun write(credentialRef: String, encrypted: EncryptedCredential) {
        val file = atomicFile(credentialRef)
        val bytes = encode(encrypted)
        var output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
            output = null
        } finally {
            if (output != null) file.failWrite(output)
            bytes.fill(0)
        }
    }

    override fun read(credentialRef: String): EncryptedCredential? {
        val bytes = try {
            AtomicFile(target(credentialRef)).readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        require(bytes.size in 1..MAX_BLOB_BYTES) { "凭据文件大小无效" }
        return decode(bytes)
    }

    override fun contains(credentialRef: String): Boolean = runCatching {
        read(credentialRef) != null
    }.getOrDefault(false)

    override fun delete(credentialRef: String) {
        AtomicFile(target(credentialRef)).delete()
    }

    private fun atomicFile(credentialRef: String): AtomicFile {
        if (directory.exists()) {
            require(directory.isDirectory && !directory.isFile) { "凭据目录无效" }
        } else {
            require(directory.mkdirs()) { "无法创建凭据目录" }
        }
        return AtomicFile(target(credentialRef))
    }

    private fun target(credentialRef: String): File {
        EncryptedProviderCredentialVault.validateCredentialRef(credentialRef)
        return File(directory, "$credentialRef.bin")
    }

    private fun encode(value: EncryptedCredential): ByteArray {
        require(value.iv.size == GCM_IV_BYTES) { "凭据 IV 无效" }
        require(value.ciphertext.size in 1..MAX_CIPHERTEXT_BYTES) { "凭据密文大小无效" }
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeByte(FORMAT_VERSION)
                output.writeByte(value.iv.size)
                output.writeInt(value.ciphertext.size)
                output.write(value.iv)
                output.write(value.ciphertext)
            }
            buffer.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): EncryptedCredential = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == FORMAT_VERSION) { "凭据版本不受支持" }
            val ivSize = input.readUnsignedByte()
            val ciphertextSize = input.readInt()
            require(ivSize == GCM_IV_BYTES) { "凭据 IV 无效" }
            require(ciphertextSize in 1..MAX_CIPHERTEXT_BYTES) { "凭据密文大小无效" }
            require(bytes.size == HEADER_BYTES + ivSize + ciphertextSize) { "凭据文件长度无效" }
            EncryptedCredential(
                iv = ByteArray(ivSize).also(input::readFully),
                ciphertext = ByteArray(ciphertextSize).also(input::readFully),
            )
        }
    } finally {
        bytes.fill(0)
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val GCM_IV_BYTES = 12
        private const val HEADER_BYTES = 6
        private const val MAX_CIPHERTEXT_BYTES = 8 * 1_024 + 32
        private const val MAX_BLOB_BYTES = HEADER_BYTES + GCM_IV_BYTES + MAX_CIPHERTEXT_BYTES
    }
}
