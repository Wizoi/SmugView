package com.smugview.app.data.prefs

import android.content.SharedPreferences
import com.smugview.app.util.KeyValueStore

/** [KeyValueStore] over an existing `SharedPreferences` file, so the stored data and its file names do not change. */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
