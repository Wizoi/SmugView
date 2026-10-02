package com.smugview.app.data.offline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Which network a kept gallery may save on (phase 6 addendum 2.1). [WIFI_ONLY] means WorkManager's UNMETERED: a Wi-Fi
 * network marked as metered (a phone hotspot) counts as mobile data. Photos saved one at a time ignore the rule.
 */
enum class OfflineNetworkRule { WIFI_ONLY, WIFI_AND_MOBILE }

/** The user's one global choice. Not Room: a setting is not data (the `SyncStateStore` rule). */
interface OfflineSettings {
    val rule: StateFlow<OfflineNetworkRule>

    /** Returns once the choice is stored, so a process death right after the tap cannot lose it. */
    suspend fun set(rule: OfflineNetworkRule)
}

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

/** For tests and previews: no Android context. */
class InMemoryOfflineSettings(initial: OfflineNetworkRule = OfflineNetworkRule.WIFI_ONLY) : OfflineSettings {
    private val state = MutableStateFlow(initial)
    override val rule: StateFlow<OfflineNetworkRule> = state
    override suspend fun set(rule: OfflineNetworkRule) { state.value = rule }
}
