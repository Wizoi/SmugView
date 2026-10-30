package com.smugview.app.data.repository

import android.content.Context

/** Production store: the `sync_state` SharedPreferences file. */
class PrefsSyncStateStore(private val context: Context) : SyncStateStore {
    private val prefs by lazy { context.getSharedPreferences("sync_state", Context.MODE_PRIVATE) }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
