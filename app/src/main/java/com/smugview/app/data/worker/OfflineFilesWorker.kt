package com.smugview.app.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smugview.app.data.offline.NetworkClass
import com.smugview.app.data.offline.OfflineDownloader
import com.smugview.app.data.offline.OfflineScheduler
import com.smugview.app.diag.DiagLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Glue between WorkManager and [OfflineDownloader] (phase 5 design 2.5): one pass, then [OfflineScheduler.after].
 * It always reports success. Retrying is the scheduler's business (a delayed unique work), because `Result.retry()`
 * would hold the adds queued behind a stuck file, and `Result.failure()` stops a chain for good (R-39, R-40).
 * Cancellation is not caught: the downloader has already put its row back to PENDING and deleted the `.part`.
 */
@HiltWorker
class OfflineFilesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: OfflineDownloader,
    private val scheduler: OfflineScheduler,
    private val log: DiagLog
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val network = inputData.getString(KEY_NETWORK)?.let { runCatching { NetworkClass.valueOf(it) }.getOrNull() }
            ?: NetworkClass.ANY
        val budget = inputData.getLong(KEY_BUDGET_MS, OfflineDownloader.DEFAULT_BUDGET_MS)
        try {
            val result = downloader.runPass(network, budget)
            // Counts and reasons only: no URL, image key, album key or password.
            log.i(
                "offline",
                "pass#${result.pass} network=$network downloaded=${result.downloaded} failed=${result.failed} " +
                    "bytes=${result.bytes} more=${result.more} stopped=${result.stoppedFor ?: "-"}"
            )
            scheduler.after(result, network)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The pass itself broke (the database, the disk). Say so; the next kick tries again. Never Result.failure().
            log.e("offline", "pass failed network=$network: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        const val KEY_NETWORK = "network"
        const val KEY_BUDGET_MS = "budgetMs"
    }
}
