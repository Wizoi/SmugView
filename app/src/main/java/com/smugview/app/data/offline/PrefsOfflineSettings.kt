package com.smugview.app.data.offline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Production: the `offline_settings` SharedPreferences file, key `galleryNetwork`. A missing or unknown value is [OfflineNetworkRule.WIFI_ONLY]. */
class PrefsOfflineSettings(
    private val prefs: SharedPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : OfflineSettings {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val rule: StateFlow<OfflineNetworkRule> = state

    override suspend fun set(rule: OfflineNetworkRule) {
        withContext(io) { prefs.edit().putString(KEY, rule.name).commit() }
        state.value = rule
    }

    private fun read(): OfflineNetworkRule =
        OfflineNetworkRule.values().firstOrNull { it.name == prefs.getString(KEY, null) } ?: OfflineNetworkRule.WIFI_ONLY

    companion object {
        const val FILE = "offline_settings"
        const val KEY = "galleryNetwork"
    }
}
