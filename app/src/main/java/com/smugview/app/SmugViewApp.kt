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

@HiltAndroidApp
class SmugViewApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The retry-wrapped Call.Factory (AppModule.provideRetryingCallFactory), not the raw
    // OkHttpClient — keeps image loads on the same non-blocking retry/error-reporting behavior as
    // API calls (see the Coil note in that provider's doc comment).
    @Inject
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
