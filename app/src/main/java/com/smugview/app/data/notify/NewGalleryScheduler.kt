package com.smugview.app.data.notify

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.smugview.app.data.db.NotifyDao
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.offline.OfflineSettings
import com.smugview.app.data.worker.NewGalleryWorker
import java.util.concurrent.TimeUnit

/**
 * One periodic job for every followed site (about every 6 hours; Android batches background work, so the delay is hours). It
 * follows the one global network rule ([OfflineSettings]): Wi-Fi only means an unmetered network. No followed site, no job.
 */
class NewGalleryScheduler(
    private val workManager: () -> WorkManager,
    private val dao: NotifyDao,
    private val settings: OfflineSettings
) {
    /** Call at app start and whenever a site is followed or unfollowed or the network rule changes. */
    suspend fun reschedule() {
        if (dao.enabledSites().isEmpty()) {
            workManager().cancelUniqueWork(NAME)
            return
        }
        val network = if (settings.rule.value == OfflineNetworkRule.WIFI_ONLY) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequest.Builder(NewGalleryWorker::class.java, INTERVAL_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        workManager().enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val NAME = "new-gallery-check"
        const val INTERVAL_HOURS = 6L
        const val FLEX_HOURS = 2L
    }
}
