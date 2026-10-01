package com.smugview.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.smugview.app.diag.DiagnosticsInitializer
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

    override fun onCreate() {
        super.onCreate()
        diagnostics.start()
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
