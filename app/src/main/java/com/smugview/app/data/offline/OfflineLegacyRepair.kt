package com.smugview.app.data.offline

import android.content.SharedPreferences
import com.smugview.app.data.repository.SyncStateStore

/**
 * Phase 5 step 5-8 (design 6.2, Q6): the one-time, silent repair of an install that upgraded from the pre-17 offline
 * code. It reads the old "gallery downloaded" flags (`offline_album_{albumKey}` in `smugview_prefs`) once, hands
 * them to [OfflineStore.repairLegacy], removes them, and only then records the `offlineLegacyRepaired` flag. A
 * process death at any point leaves the flag unset, the next start runs it again, and every step is idempotent.
 */
class OfflineLegacyRepair(
    private val store: OfflineStore,
    private val state: SyncStateStore,
    private val prefs: SharedPreferences
) {
    /** Returns the repair that ran, or null when it had already run. */
    suspend fun runOnce(): OfflineStore.LegacyRepair? {
        if (state.getBoolean(FLAG)) return null
        val all = prefs.all
        val finished = all.filter { (k, v) -> k.startsWith(PREFIX) && v == true }.keys.map { it.removePrefix(PREFIX) }.toSet()
        val result = store.repairLegacy(finished)
        val stale = all.keys.filter { it.startsWith(PREFIX) }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.commit()
        state.putBoolean(FLAG, true)
        return result
    }

    companion object {
        const val FLAG = "offlineLegacyRepaired"
        const val PREFIX = "offline_album_"
    }
}

/**
 * What the app does at process start for offline files, in the one order that is safe: settle what the old code left
 * first, and only then wake the scheduler. Waking it first would download every legacy photo again (the 5-5 hazard).
 * Neither step may stop the app from starting, so a failure is swallowed.
 */
class OfflineStartup(
    private val repair: OfflineLegacyRepair,
    private val scheduler: OfflineScheduler
) {
    suspend fun run() {
        // A failed repair (a database or disk error) leaves the old rows PENDING: not waking the scheduler is then the
        // safe choice, the next start tries again. A save made in the meantime still kicks the scheduler itself.
        if (runCatching { repair.runOnce() }.isSuccess) runCatching { scheduler.kickIfWanted() }
    }
}
