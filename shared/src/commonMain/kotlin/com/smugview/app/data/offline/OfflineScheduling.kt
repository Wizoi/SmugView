package com.smugview.app.data.offline

/**
 * What the offline code asks of "whatever decides when a download pass runs": Android's WorkManager scheduler, and on iOS a background-task
 * request plus a run when the app opens.
 */
interface OfflineScheduling {
    /** Something was added or removed: run a pass on every chain that has work. Cheap; safe to call often. */
    suspend fun kick()

    /** At app start: only when something is not DONE (and not permanently failed). */
    suspend fun kickIfWanted()

    /** Called after a pass: append the next run when files are still due, else one delayed run for the earliest retry. */
    suspend fun after(result: PassResult, network: NetworkClass)
}
