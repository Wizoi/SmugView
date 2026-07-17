package com.smugview.app.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [PasswordStore] for JVM unit tests (no Android keystore required). */
class FakePasswordStore(
    initial: Map<String, String> = emptyMap()
) : PasswordStore {
    private val map = LinkedHashMap<String, String>(initial)
    private val _unlockedKeys = MutableStateFlow(keysWithPassword())
    override val unlockedKeys: StateFlow<Set<String>> = _unlockedKeys

    override fun getPassword(key: String?): String? = key?.let { map[it] }

    override fun savePassword(key: String, value: String) {
        map[key] = value
        _unlockedKeys.value = keysWithPassword()
    }

    override fun remove(key: String) {
        map.remove(key)
        _unlockedKeys.value = keysWithPassword()
    }

    override fun all(): Map<String, String> = map.toMap()

    private fun keysWithPassword(): Set<String> =
        map.filterValues { it.isNotEmpty() }.keys.toSet()
}
