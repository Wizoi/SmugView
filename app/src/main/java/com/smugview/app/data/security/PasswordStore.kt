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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Counts every save and remove, including a save that changes the password under a key that already has one (the key set
     * does not change then, so [unlockedKeys] does not emit). Whoever must react to a changed VALUE collects this (N9).
     */
    val revision: StateFlow<Long>

    /** True when the phone's secure storage could not be opened: passwords then live in memory only, until the app closes (R-25). */
    val keptOnlyThisSession: StateFlow<Boolean>

    fun getPassword(key: String?): String?
    fun savePassword(key: String, value: String)
    fun remove(key: String)
    /** Snapshot of all stored (key -> password) entries. */
    fun all(): Map<String, String>
}

/**
 * Encrypted-at-rest implementation backed by [EncryptedSharedPreferences] (built by [prefsFactory], a seam so a test can play a
 * broken Keystore).
 *
 * A password is NEVER written in plain text (R-25, owner decision Q5 (a)). If the secure store cannot be opened, a typed password
 * is held in memory: it works until the app closes, [keptOnlyThisSession] says so, and the prompt tells the user once.
 *
 * Passwords an older version left in plain files (`smugview_passwords_fallback`, written when the Keystore failed, and the
 * pre-encryption `smugview_passwords`) move into the secure store the next time it opens, and only after the secure store has
 * confirmed the write (`commit()` returned true) are those files emptied and deleted. If the move fails, or the Keystore still
 * fails, the plain files are left alone (deleting them would lose the user's passwords) and their entries are read into memory so
 * they keep working this session. Run twice, the move changes nothing: it is safe to kill at any point.
 */
@Singleton
class EncryptedPasswordStore internal constructor(
    private val context: Context,
    private val prefsFactory: (Context) -> SharedPreferences
) : PasswordStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context, ::createEncryptedPrefs)

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _keptOnlyThisSession = MutableStateFlow(false)
    override val keptOnlyThisSession: StateFlow<Boolean> = _keptOnlyThisSession.asStateFlow()

    private val _unlockedKeys = MutableStateFlow<Set<String>>(emptySet())
    override val unlockedKeys: StateFlow<Set<String>> = _unlockedKeys.asStateFlow()

    /** The secure store, or null when it could not be opened. Opening also runs the plain-file move and primes [unlockedKeys]. */
    private val secure: SharedPreferences? by lazy { openSecure() }

    /**
     * Memory only, never on disk: every password while [secure] is null, and the entries a failed move still holds in plain
     * files. [secure] wins over it for the same key.
     */
    private val inMemory = ConcurrentHashMap<String, String>()

    override fun getPassword(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return secure?.getString(key, null) ?: inMemory[key]
    }

    override fun savePassword(key: String, value: String) {
        val store = secure
        if (store != null) {
            store.edit().putString(key, value).apply()
            inMemory.remove(key)
        } else {
            inMemory[key] = value
        }
        changed()
    }

    override fun remove(key: String) {
        secure?.edit()?.remove(key)?.apply()
        inMemory.remove(key)
        changed()
    }

    override fun all(): Map<String, String> {
        val result = HashMap<String, String>(inMemory)
        secure?.all?.forEach { (k, v) -> if (v is String) result[k] = v }
        return result
    }

    private fun changed() {
        _unlockedKeys.value = keysWithPassword()
        _revision.value++
    }

    private fun keysWithPassword(): Set<String> =
        all().filterValues { it.isNotEmpty() }.keys.toSet()

    private fun openSecure(): SharedPreferences? {
        val store = try {
            prefsFactory(context)
        } catch (e: Exception) {
            // The Keystore-backed store can't be created (device or Keystore trouble). Nothing is written in plain text instead.
            Log.e(TAG, "Secure password storage unavailable; passwords are kept in memory for this session only", e)
            null
        }
        val plain = readPlaintextFiles()
        if (store == null) {
            inMemory.putAll(plain.entries)
            _keptOnlyThisSession.value = true
        } else {
            if (!moveIntoSecure(store, plain)) inMemory.putAll(plain.entries)
        }
        // Prime the observable set now that the store is loaded. (all() reads `secure`, which is being built: use what we have.)
        val keys = HashSet<String>()
        inMemory.forEach { (k, v) -> if (v.isNotEmpty()) keys += k }
        store?.all?.forEach { (k, v) -> if (v is String && v.isNotEmpty()) keys += k }
        _unlockedKeys.value = keys
        return store
    }

    /** What the plain files hold: the entries (the fallback file wins over the older legacy file) and which files exist. */
    private class PlainFiles(val entries: Map<String, String>, val present: List<String>)

    private fun readPlaintextFiles(): PlainFiles {
        val entries = LinkedHashMap<String, String>()
        val present = mutableListOf<String>()
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        // Oldest first, so the newer file's value wins.
        for (name in listOf(LEGACY_PLAINTEXT_FILE, FALLBACK_FILE)) {
            // Only opened when the file exists, so reading creates nothing.
            if (!File(dir, "$name.xml").exists()) continue
            present += name
            for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (v is String) entries[k] = v
            }
        }
        return PlainFiles(entries, present)
    }

    /** True when the plain files are gone (nothing to move, or moved and confirmed). False leaves them as they are. */
    private fun moveIntoSecure(target: SharedPreferences, plain: PlainFiles): Boolean {
        if (plain.present.isEmpty()) return true
        if (plain.entries.isNotEmpty()) {
            val written = try {
                val editor = target.edit()
                for ((k, v) in plain.entries) editor.putString(k, v)
                editor.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Could not move saved passwords into secure storage; they stay where they are for now", e)
                false
            }
            if (!written) return false
        }
        for (name in plain.present) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
        SmugLog.i(TAG) { "Moved ${plain.entries.size} saved password entries into secure storage" }
        return true
    }

    companion object {
        internal fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private const val TAG = "PasswordStore"
        private const val ENCRYPTED_FILE = "smugview_passwords_enc"
        private const val FALLBACK_FILE = "smugview_passwords_fallback"
        private const val LEGACY_PLAINTEXT_FILE = "smugview_passwords"
    }
}
