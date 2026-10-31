package com.smugview.app.data.cast

import com.smugview.app.util.KeyValueStore

/** The sentence iOS shows before the first scan; also the `NSLocalNetworkUsageDescription` (docs/design/ios-casting.md, decision 4). */
const val CAST_EXPLANATION = "SmugView looks for Chromecasts and other cast devices on your Wi-Fi so you can show photos on your TV."

/** Whether the one-time "why we look on your Wi-Fi" explanation is still owed. Only iOS provides one; Android never does. */
interface CastExplainer {
    fun shouldExplain(): Boolean
    fun markExplained()
}

/** Remembers in [store] that the explanation was accepted, so it shows once per install. "Not now" leaves it owed. */
class KeyValueCastExplainer(private val store: KeyValueStore) : CastExplainer {
    override fun shouldExplain(): Boolean = store.getLong(KEY, 0L) == 0L
    override fun markExplained() = store.putLong(KEY, 1L)

    private companion object {
        const val KEY = "cast_local_network_explained"
    }
}
