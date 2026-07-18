package com.smugview.app.data.security
import com.smugview.app.util.SmugLog

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized store for SmugMug gallery/node unlock passwords.
 *
 * Replaces the previous scattered plaintext `SharedPreferences("smugview_passwords")` access in
 * both [com.smugview.app.ui.viewmodel.SmugViewModel] and
 * [com.smugview.app.data.repository.SmugMugRepository].
 *
 * [unlockedKeys] exposes the set of keys (nodeIds / albumKeys) that currently have a saved
 * password so UI can observe unlock state reactively instead of polling a blocking predicate.
 *
 * This is an interface so it can be faked in JVM unit tests (the production
 * [EncryptedPasswordStore] requires the Android keystore).
 */
interface PasswordStore {
    val unlockedKeys: StateFlow<Set<String>>
    fun getPassword(key: String?): String?
    fun savePassword(key: String, value: String)
    fun remove(key: String)
    /** Snapshot of all stored (key -> password) entries. */
    fun all(): Map<String, String>
}

/**
 * Encrypted-at-rest implementation backed by [EncryptedSharedPreferences]. Existing plaintext
 * entries from the legacy file are migrated once on first use and the old file is cleared.
 */
@Singleton
class EncryptedPasswordStore @Inject constructor(
    @ApplicationContext private val context: Context
) : PasswordStore {

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    private val _unlockedKeys = MutableStateFlow<Set<String>>(emptySet())
    override val unlockedKeys: StateFlow<Set<String>> = _unlockedKeys.asStateFlow()

    override fun getPassword(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return prefs.getString(key, null)
    }

    override fun savePassword(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        refreshUnlockedKeys()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
        refreshUnlockedKeys()
    }

    override fun all(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all.filterValues { it is String } as Map<String, String>
    }

    private fun refreshUnlockedKeys() {
        _unlockedKeys.value = prefs.all
            .filterValues { it is String && it.isNotEmpty() }
            .keys
            .toSet()
    }

    private fun buildPrefs(): SharedPreferences {
        val store = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If the keystore-backed store can't be created (rare device/keystore corruption),
            // fall back to a private file so the app still functions rather than crashing.
            Log.e(TAG, "EncryptedSharedPreferences unavailable; falling back to private prefs", e)
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
        migratePlaintextInto(store)
        // Prime the observable set now that prefs are loaded.
        _unlockedKeys.value = store.all
            .filterValues { it is String && it.isNotEmpty() }
            .keys
            .toSet()
        return store
    }

    /** One-time migration of legacy plaintext passwords into the encrypted store. */
    private fun migratePlaintextInto(target: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PLAINTEXT_FILE, Context.MODE_PRIVATE)
        val legacyEntries = legacy.all
        if (legacyEntries.isEmpty()) return
        val editor = target.edit()
        for ((key, value) in legacyEntries) {
            if (value is String) editor.putString(key, value)
        }
        editor.apply()
        legacy.edit().clear().apply()
        SmugLog.i(TAG) { "Migrated ${legacyEntries.size} password entries to encrypted storage" }
    }

    companion object {
        private const val TAG = "PasswordStore"
        private const val ENCRYPTED_FILE = "smugview_passwords_enc"
        private const val FALLBACK_FILE = "smugview_passwords_fallback"
        private const val LEGACY_PLAINTEXT_FILE = "smugview_passwords"
    }
}
