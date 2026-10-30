package com.smugview.app.data.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/** For tests and previews: no Android context. */
class InMemoryOfflineSettings(initial: OfflineNetworkRule = OfflineNetworkRule.WIFI_ONLY) : OfflineSettings {
    private val state = MutableStateFlow(initial)
    override val rule: StateFlow<OfflineNetworkRule> = state
    override suspend fun set(rule: OfflineNetworkRule) { state.value = rule }
}
