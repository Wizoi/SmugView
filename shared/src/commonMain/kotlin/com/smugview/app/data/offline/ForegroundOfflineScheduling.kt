package com.smugview.app.data.offline

import com.smugview.app.data.db.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The iOS [OfflineScheduling]: download passes run in [scope] while the app is running. iOS has no WorkManager, so a pass that the app
 * is not alive for waits for the next launch (`kickIfWanted`) or for a background task that calls [runBackgroundPass].
 *
 *  - One worker coroutine runs one pass at a time. [kick] only drops a token into a conflated channel, so a kick while a pass runs
 *    is coalesced into ONE more pass (it sees what was added meanwhile).
 *  - The pass class follows the network: Wi-Fi (not metered) runs [NetworkClass.UNMETERED] (everything wanted), any other connection
 *    runs [NetworkClass.ANY] (the downloader applies the global rule), no connection runs nothing and waits for the network to come back.
 *  - [after]: a pass that stopped with files still due runs again at once; else one retry is scheduled after [delayFn] for the earliest
 *    retryable failure (never sooner than [MIN_RETRY_DELAY_MS]); nothing due means nothing scheduled.
 *
 * The three lambdas keep the class testable without a database; [create] wires the real [OfflineDownloader] and [OfflineStore].
 */
class ForegroundOfflineScheduling(
    private val scope: CoroutineScope,
    private val network: Flow<NetworkKind>,
    private val settings: OfflineSettings,
    private val runPass: suspend (NetworkClass) -> PassResult,
    private val countWanted: suspend () -> Int,
    private val earliestRetryAt: suspend (takeGalleryOnly: Boolean) -> Long?,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = ::nowMillis
) : OfflineScheduling {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private var retryJob: Job? = null

    init {
        scope.launch {
            for (token in trigger) {
                try {
                    runOnePass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The pass itself broke (the database, the disk): the next kick tries again.
                }
            }
        }
        scope.launch {
            // A network that comes back is a reason to look again; the pass decides whether anything is due.
            var previous: NetworkKind? = null
            network.distinctUntilChanged().collect { kind ->
                // Back from no connection, or onto Wi-Fi (which unlocks Wi-Fi-only work); metered to metered changes nothing.
                val cameBack = previous != null && (previous == NetworkKind.NONE && kind != NetworkKind.NONE || kind == NetworkKind.UNMETERED)
                previous = kind
                if (cameBack && countWanted() > 0) trigger.trySend(Unit)
            }
        }
    }

    override suspend fun kick() {
        trigger.trySend(Unit)
    }

    override suspend fun kickIfWanted() {
        if (countWanted() > 0) trigger.trySend(Unit)
    }

    override suspend fun after(result: PassResult, network: NetworkClass) {
        if (result.more) {
            trigger.trySend(Unit)
            return
        }
        val takeGalleryOnly = network == NetworkClass.UNMETERED || settings.rule.value == OfflineNetworkRule.WIFI_AND_MOBILE
        val at = earliestRetryAt(takeGalleryOnly) ?: return
        val wait = maxOf(MIN_RETRY_DELAY_MS, at - clock())
        retryJob?.cancel()
        retryJob = scope.launch {
            delayFn(wait)
            trigger.trySend(Unit)
        }
    }

    /**
     * One pass for an iOS background task (`BGProcessingTask`): the caller owns the time box (cancel the coroutine on expiry; the
     * downloader puts the row back to PENDING). Returns an empty result when there is no connection. The caller re-submits its
     * task when [PassResult.more]. It does not call [after], so no timer outlives the task.
     */
    suspend fun runBackgroundPass(): PassResult {
        val networkClass = classFor(network.first()) ?: return PassResult(0, 0, 0, 0L, more = false)
        return runPass(networkClass)
    }

    private suspend fun runOnePass() {
        val networkClass = classFor(network.first()) ?: return
        val result = runPass(networkClass)
        after(result, networkClass)
    }

    private fun classFor(kind: NetworkKind): NetworkClass? = when (kind) {
        NetworkKind.NONE -> null
        NetworkKind.UNMETERED -> NetworkClass.UNMETERED
        NetworkKind.METERED_WIFI, NetworkKind.METERED_OTHER -> NetworkClass.ANY
    }

    companion object {
        /** No retry sooner than this, even for a row that is "due now" (an OFFLINE failure has no delay of its own). Same as Android. */
        const val MIN_RETRY_DELAY_MS = 30_000L

        fun create(
            scope: CoroutineScope,
            network: Flow<NetworkKind>,
            settings: OfflineSettings,
            downloader: OfflineDownloader,
            store: OfflineStore
        ) = ForegroundOfflineScheduling(
            scope, network, settings,
            runPass = { downloader.runPass(it) },
            countWanted = { store.countWanted() },
            earliestRetryAt = { store.earliestRetryAt(it) }
        )
    }
}
