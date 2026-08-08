package com.agentdeck.app.data.secure

import android.content.Context
import androidx.core.content.edit
import java.security.KeyStore

/** Removes credentials stored by the v0.1.0 skeleton. CLI tools own authentication now. */
object LegacyCredentialCleaner {
    private const val LEGACY_PREFS_NAME = "agentdeck_secure_prefs"
    private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    private const val MIGRATION_PREFS_NAME = "agentdeck_migrations"
    private const val KEY_CREDENTIALS_REMOVED = "v0_1_0_credentials_removed"

    fun clear(context: Context) {
        val migrations = context.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)
        if (migrations.getBoolean(KEY_CREDENTIALS_REMOVED, false)) return

        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        val keyRemoved = runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }.isSuccess
        if (keyRemoved) {
            migrations.edit { putBoolean(KEY_CREDENTIALS_REMOVED, true) }
        }
    }
}
