package com.smugview.app.util

import platform.Foundation.NSUserDefaults

/**
 * [KeyValueStore] over one `UserDefaults` suite. Moved out of IosApp.kt so the settings and sync-state stores can share it.
 * Written without a Mac: the macOS CI job is its first compile.
 */
class UserDefaultsStore(suiteName: String) : KeyValueStore {
    private val defaults = NSUserDefaults(suiteName = suiteName)
    override fun getString(key: String, default: String?): String? = defaults.stringForKey(key) ?: default
    override fun putString(key: String, value: String) = defaults.setObject(value, forKey = key)
    override fun getLong(key: String, default: Long): Long = if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)
    override fun putLong(key: String, value: Long) = defaults.setInteger(value, forKey = key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}
