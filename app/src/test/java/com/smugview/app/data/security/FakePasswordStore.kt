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
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    /** Tests set this to play a phone whose secure storage does not work. */
    val keptOnlyThisSessionFlow = MutableStateFlow(false)
    override val keptOnlyThisSession: StateFlow<Boolean> = keptOnlyThisSessionFlow

    override fun getPassword(key: String?): String? = key?.let { map[it] }

    override fun savePassword(key: String, value: String) {
        map[key] = value
        _unlockedKeys.value = keysWithPassword()
        _revision.value++
    }

    override fun remove(key: String) {
        map.remove(key)
        _unlockedKeys.value = keysWithPassword()
        _revision.value++
    }

    override fun all(): Map<String, String> = map.toMap()

    private fun keysWithPassword(): Set<String> =
        map.filterValues { it.isNotEmpty() }.keys.toSet()
}
