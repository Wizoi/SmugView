package com.smugview.app.data.security

import com.smugview.shared.platform.SimpleLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The platform's secure storage (iOS: the Keychain). Kept tiny so [SecureStorePasswordStore] is testable without it. */
interface SecureStorage {
    /** The saved value, or null when there is none or the storage could not be read. */
    fun get(key: String): String?

    /** True when the value is stored. False means the storage could not be used (the caller then keeps it in memory). */
    fun put(key: String, value: String): Boolean

    fun remove(key: String)

    /** Every stored (key to value), or null when the storage cannot be used at all. */
    fun all(): Map<String, String>?
}

/**
 * [PasswordStore] over a [SecureStorage]. Mirrors Android's `EncryptedPasswordStore`: a password is never written anywhere
 * else; when the storage cannot be used the password is held in memory, works until the app closes, and [keptOnlyThisSession]
 * says so. The storage wins over memory for the same key.
 */
class SecureStorePasswordStore(private val storage: SecureStorage) : PasswordStore {
    private val lock = SimpleLock()
    private val inMemory = HashMap<String, String>()

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()
    private val _keptOnlyThisSession = MutableStateFlow(false)
    override val keptOnlyThisSession: StateFlow<Boolean> = _keptOnlyThisSession.asStateFlow()
    private val _unlockedKeys = MutableStateFlow<Set<String>>(emptySet())
    override val unlockedKeys: StateFlow<Set<String>> = _unlockedKeys.asStateFlow()

    init {
        val existing = storage.all()
        if (existing == null) _keptOnlyThisSession.value = true
        _unlockedKeys.value = keysWithPassword(existing.orEmpty())
    }

    override fun getPassword(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return storage.get(key) ?: lock.withLock { inMemory[key] }
    }

    override fun savePassword(key: String, value: String) {
        val stored = storage.put(key, value)
        lock.withLock {
            if (stored) inMemory.remove(key) else inMemory[key] = value
        }
        if (!stored) _keptOnlyThisSession.value = true
        changed()
    }

    override fun remove(key: String) {
        storage.remove(key)
        lock.withLock { inMemory.remove(key) }
        changed()
    }

    override fun all(): Map<String, String> = merged(storage.all().orEmpty())

    private fun changed() {
        _unlockedKeys.value = keysWithPassword(storage.all().orEmpty())
        _revision.value = _revision.value + 1
    }

    private fun merged(stored: Map<String, String>): Map<String, String> {
        val result = HashMap<String, String>(lock.withLock { HashMap(inMemory) })
        result.putAll(stored)
        return result
    }

    private fun keysWithPassword(stored: Map<String, String>): Set<String> =
        merged(stored).filterValues { it.isNotEmpty() }.keys.toSet()
}
