package com.smugview.app.util

/**
 * Small persisted settings and timestamps (sort orders, the active site, "last searched at"). Android keeps them in the same
 * `SharedPreferences` files as before (no migration); iOS will use `UserDefaults`.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}
