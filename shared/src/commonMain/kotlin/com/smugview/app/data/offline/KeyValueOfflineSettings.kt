package com.smugview.app.data.offline

import com.smugview.app.util.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [OfflineSettings] over a [KeyValueStore] (UserDefaults on iOS). Same key and default as Android's `PrefsOfflineSettings`:
 * key `galleryNetwork` holds the enum name; a missing or unknown value is [OfflineNetworkRule.WIFI_ONLY].
 */
class KeyValueOfflineSettings(private val store: KeyValueStore) : OfflineSettings {
    private val state = MutableStateFlow(read())
    override val rule: StateFlow<OfflineNetworkRule> = state

    override suspend fun set(rule: OfflineNetworkRule) {
        store.putString(KEY, rule.name)
        state.value = rule
    }

    private fun read(): OfflineNetworkRule =
        OfflineNetworkRule.entries.firstOrNull { it.name == store.getString(KEY, null) } ?: OfflineNetworkRule.WIFI_ONLY

    companion object {
        const val KEY = "galleryNetwork"
    }
}
