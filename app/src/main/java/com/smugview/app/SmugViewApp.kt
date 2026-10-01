package com.smugview.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.smugview.app.data.offline.OfflineScheduler
import com.smugview.app.data.offline.OfflineStartup
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.diag.DiagnosticsInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Call
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class SmugViewApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The image Call.Factory (AppModule.provideImageCallFactory): retry and telemetry like the API, but no
    // HTTP cache, so photos never evict API JSON from it (R-37). Coil has its own disk cache.
    @Inject
    @field:Named("images")
    lateinit var callFactory: Call.Factory

    @Inject
    lateinit var diagnostics: DiagnosticsInitializer

    @Inject
    lateinit var offlineScheduler: OfflineScheduler

    @Inject
    lateinit var offlineStartup: OfflineStartup

    @Inject
    lateinit var offlineStore: OfflineStore

    @Inject
    lateinit var repository: SmugMugRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        diagnostics.start()
        // Settle what the pre-17 offline code left (once), THEN queue a pass for the files still wanted (from before
        // the process died, or a failed retry). The order is the point: see OfflineStartup. Never blocks start-up.
        appScope.launch { offlineStartup.run() }
        // A password root came into session: a kept gallery that was permanently locked is worth listing again.
        appScope.launch {
            repository.unlocks.sessionEpoch.drop(1).collect {
                runCatching { if (offlineStore.requeueLockedGalleries() > 0) offlineScheduler.kick() }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .callFactory(callFactory)
            .respectCacheHeaders(false)
            .build()
    }
}
