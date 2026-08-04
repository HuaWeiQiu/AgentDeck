package com.agentdeck.app.data.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores API keys outside Room. Values are encrypted at rest.
 */
class SecureKeyStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun put(keyRef: String, secret: String) {
        prefs.edit().putString(keyRef, secret).apply()
    }

    fun get(keyRef: String): String? = prefs.getString(keyRef, null)

    fun delete(keyRef: String) {
        prefs.edit().remove(keyRef).apply()
    }

    companion object {
        const val PREFS_NAME = "agentdeck_secure_prefs"
    }
}
