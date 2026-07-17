package com.smugview.app.data.security

/**
 * Thin SharedPreferences-shaped adapter over [PasswordStore].
 *
 * Lets existing call sites keep their familiar `getString(...)` / `edit().putString(...).apply()`
 * shape while the values are actually persisted through the encrypted [PasswordStore] (and the
 * observable [PasswordStore.unlockedKeys] set stays in sync).
 */
class PasswordPrefsCompat(private val store: PasswordStore) {

    fun getString(key: String, default: String?): String? = store.getPassword(key) ?: default

    /** Snapshot of all stored (key -> password) entries. */
    val all: Map<String, String> get() = store.all()

    fun edit(): Editor = Editor(store)

    class Editor(private val store: PasswordStore) {
        private val puts = LinkedHashMap<String, String>()
        private val removes = LinkedHashSet<String>()

        fun putString(key: String, value: String?): Editor {
            if (value != null) {
                puts[key] = value
                removes.remove(key)
            }
            return this
        }

        fun remove(key: String): Editor {
            removes.add(key)
            puts.remove(key)
            return this
        }

        fun apply() {
            removes.forEach { store.remove(it) }
            puts.forEach { (k, v) -> store.savePassword(k, v) }
        }
    }
}
