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

interface ExtensionCredentialVault {
    fun save(credentialRef: String, secret: ByteArray)
    fun load(credentialRef: String): ByteArray?
    fun contains(credentialRef: String): Boolean
    fun delete(credentialRef: String)
    fun pruneExcept(validCredentialRefs: Set<String>) = Unit
}

internal interface PrunableCredentialBlobStore {
    fun pruneExcept(validCredentialRefs: Set<String>)
}

internal class EncryptedExtensionCredentialVault(
    private val cipher: CredentialCipher,
    private val store: CredentialBlobStore,
) : ExtensionCredentialVault {
    override fun save(credentialRef: String, secret: ByteArray) {
        validateRef(credentialRef)
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES) { "MCP Token 长度无效" }
        require(secret.none { it == 0.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
            "MCP Token 包含非法字符"
        }
        val plaintext = secret.copyOf()
        try {
            store.write(credentialRef, cipher.encrypt(plaintext, aad(credentialRef)))
        } finally {
            plaintext.fill(0)
        }
    }

    override fun load(credentialRef: String): ByteArray? {
        validateRef(credentialRef)
        val encrypted = store.read(credentialRef) ?: return null
        return cipher.decrypt(encrypted, aad(credentialRef)).also { secret ->
            if (secret.isEmpty() || secret.size > MAX_SECRET_BYTES ||
                secret.any { it == 0.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() }
            ) {
                secret.fill(0)
                error("已保存的 MCP Token 无效")
            }
        }
    }

    override fun contains(credentialRef: String): Boolean {
        validateRef(credentialRef)
        return store.contains(credentialRef)
    }

    override fun delete(credentialRef: String) {
        validateRef(credentialRef)
        store.delete(credentialRef)
    }

    override fun pruneExcept(validCredentialRefs: Set<String>) {
        validCredentialRefs.forEach(::validateRef)
        (store as? PrunableCredentialBlobStore)?.pruneExcept(validCredentialRefs)
    }

    private fun aad(ref: String) = "agentdeck-extension-v1:$ref".toByteArray()

    companion object {
        private const val MAX_SECRET_BYTES = 8 * 1_024
        internal fun validateRef(value: String) {
            require(value.matches(Regex("extcred_[a-f0-9]{32}"))) { "扩展凭据引用无效" }
        }
    }
}

class AndroidExtensionCredentialVault(context: Context) : ExtensionCredentialVault by
    EncryptedExtensionCredentialVault(
        cipher = ExtensionKeystoreCipher(),
        store = ExtensionCredentialBlobStore(context),
    )

private class ExtensionKeystoreCipher : CredentialCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad)
        return EncryptedCredential(cipher.iv.copyOf(), cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: EncryptedCredential, aad: ByteArray): ByteArray {
        require(encrypted.iv.size == GCM_IV_BYTES) { "扩展凭据 IV 无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, encrypted.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
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
            generateKey()
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "agentdeck_extension_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}

private class ExtensionCredentialBlobStore(context: Context) : CredentialBlobStore, PrunableCredentialBlobStore {
    private val directory = File(context.noBackupFilesDir, "extension_credentials")

    override fun write(credentialRef: String, encrypted: EncryptedCredential) {
        val bytes = encode(encrypted)
        val atomic = AtomicFile(target(credentialRef))
        var output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
            output = null
        } finally {
            if (output != null) atomic.failWrite(output)
            bytes.fill(0)
        }
    }

    override fun read(credentialRef: String): EncryptedCredential? {
        val bytes = try {
            AtomicFile(target(credentialRef)).readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        return decode(bytes)
    }

    override fun contains(credentialRef: String): Boolean = runCatching { read(credentialRef) != null }
        .getOrDefault(false)

    override fun delete(credentialRef: String) {
        AtomicFile(target(credentialRef)).delete()
    }

    override fun pruneExcept(validCredentialRefs: Set<String>) {
        if (!directory.isDirectory) return
        directory.listFiles()
            .orEmpty()
            .mapNotNull { file -> CREDENTIAL_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1) }
            .distinct()
            .forEach { ref ->
                if (ref !in validCredentialRefs) AtomicFile(File(directory, "$ref.bin")).delete()
            }
    }

    private fun target(ref: String): File {
        EncryptedExtensionCredentialVault.validateRef(ref)
        check(directory.mkdirs() || directory.isDirectory) { "无法创建扩展凭据目录" }
        return File(directory, "$ref.bin")
    }

    private fun encode(value: EncryptedCredential): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte(FORMAT_VERSION)
            output.writeByte(value.iv.size)
            output.writeInt(value.ciphertext.size)
            output.write(value.iv)
            output.write(value.ciphertext)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): EncryptedCredential = try {
        require(bytes.size in 1..MAX_BLOB_BYTES) { "扩展凭据文件大小无效" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == FORMAT_VERSION) { "扩展凭据版本不受支持" }
            val ivSize = input.readUnsignedByte()
            val ciphertextSize = input.readInt()
            require(ivSize == GCM_IV_BYTES && ciphertextSize in 1..MAX_CIPHERTEXT_BYTES) {
                "扩展凭据文件无效"
            }
            require(bytes.size == HEADER_BYTES + ivSize + ciphertextSize) { "扩展凭据文件长度无效" }
            EncryptedCredential(
                ByteArray(ivSize).also(input::readFully),
                ByteArray(ciphertextSize).also(input::readFully),
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
        private val CREDENTIAL_FILE_PATTERN =
            Regex("(extcred_[a-f0-9]{32})\\.bin(?:\\.new|\\.bak)?")
    }
}
