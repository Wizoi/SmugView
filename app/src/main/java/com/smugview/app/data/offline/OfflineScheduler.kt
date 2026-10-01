package com.smugview.app.data.offline

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.smugview.app.data.worker.OfflineFilesWorker
import java.util.concurrent.TimeUnit

/**
 * Decides when an [OfflineDownloader] pass runs (phase 5 design 2.5). WorkManager only knows "run when the network
 * and storage constraints hold"; everything else (what is due, what waits for Wi-Fi, when a retry is worth trying)
 * is a question to the database through [OfflineStore].
 *
 * Unique work, one chain per network class:
 *  - `offline-files` (CONNECTED): every wanted file that may use mobile data (single photos, galleries the user
 *    allowed on it). `APPEND_OR_REPLACE`: a second [kick] while a pass runs is queued BEHIND it, so two passes never
 *    run at once and a photo added just after the running pass looked is picked up by the appended run.
 *  - `offline-files-wifi` (UNMETERED): the `wifiOnly` rows (Q3), only enqueued while one is wanted.
 *  - `offline-files-retry` / `offline-files-wifi-retry`: one delayed run for the earliest retryable failure.
 *    `REPLACE`: there is only ever one, with the delay of the earliest row.
 * A pass never ends in `Result.retry()` or `Result.failure()`: a stuck file must not hold back the adds behind it.
 * No foreground service and no notification.
 */
class OfflineScheduler(
    private val workManager: () -> WorkManager,
    private val store: OfflineStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Something was added or removed: run a pass on every chain that has work. Cheap; safe to call often. */
    suspend fun kick() {
        enqueue(NetworkClass.ANY, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMs = 0)
        if (store.countWantedWifiOnly() > 0) enqueue(NetworkClass.UNMETERED, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMs = 0)
    }

    /** At app start: only when something is not DONE (and not permanently failed). */
    suspend fun kickIfWanted() {
        if (store.countWanted() > 0) kick()
    }

    /**
     * Called by the worker after a pass. [PassResult.more]: the budget ran out with files due, so append the next
     * run. Otherwise, when retryable failures remain, enqueue one delayed run for the earliest. Never both.
     */
    suspend fun after(result: PassResult, network: NetworkClass) {
        if (result.more) {
            enqueue(network, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMs = 0)
            return
        }
        val unmetered = network == NetworkClass.UNMETERED
        val at = store.earliestRetryAt(unmetered) ?: return
        val delay = maxOf(MIN_RETRY_DELAY_MS, at - clock())
        enqueue(network, ExistingWorkPolicy.REPLACE, delayMs = delay, retry = true)
    }

    private fun enqueue(network: NetworkClass, policy: ExistingWorkPolicy, delayMs: Long, retry: Boolean = false) {
        val unmetered = network == NetworkClass.UNMETERED
        val request = OneTimeWorkRequest.Builder(OfflineFilesWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInputData(Data.Builder().putString(OfflineFilesWorker.KEY_NETWORK, network.name).build())
            .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()
        workManager().enqueueUniqueWork(nameOf(network, retry), policy, request)
    }

    companion object {
        const val NAME = "offline-files"
        const val NAME_WIFI = "offline-files-wifi"
        const val NAME_RETRY = "offline-files-retry"
        const val NAME_WIFI_RETRY = "offline-files-wifi-retry"

        /**
         * No retry sooner than this, even for a row that is "due now": an OFFLINE failure has no delay of its own (the
         * network constraint is the wait), and a device that reports a network it cannot use would loop.
         */
        const val MIN_RETRY_DELAY_MS = 30_000L

        fun nameOf(network: NetworkClass, retry: Boolean): String = when {
            network == NetworkClass.UNMETERED && retry -> NAME_WIFI_RETRY
            network == NetworkClass.UNMETERED -> NAME_WIFI
            retry -> NAME_RETRY
            else -> NAME
        }
    }
}
