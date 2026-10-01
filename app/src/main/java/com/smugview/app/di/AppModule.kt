package com.smugview.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.smugview.app.data.api.CredentialEpoch
import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.SessionCookieJar
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.api.cachePolicyInterceptor
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The OkHttp logging interceptor used in debug builds (R-52). Every logged line goes through
 * [redactor] (API key, passwords, JSON secrets, cookie lines), and the credential headers are
 * masked by OkHttp itself. Release builds pass `Level.NONE`, so nothing is logged there.
 */
internal fun debugHttpLogger(
    redactor: com.smugview.app.diag.Redactor,
    level: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BODY,
    sink: (String) -> Unit
): HttpLoggingInterceptor = HttpLoggingInterceptor { sink(redactor.redact(it)) }.apply {
    this.level = level
    redactHeader("Authorization")
    redactHeader("Cookie")
    redactHeader("Set-Cookie")
}

/**
 * Cache Interceptor (a NETWORK interceptor): force OkHttp to cache GET responses by replacing
 * 'no-cache/no-store' with a 5-minute cache header. SmugMug itself sends `private, no-store, no-cache,
 * max-age=0` (findings V11). Search/unlock/Text requests are excluded: they must always hit
 * the network. A request that itself says `Cache-Control: no-cache` skips the cache lookup in OkHttp
 * (R-35), which is how a forced listing gets fresh data inside the 5-minute window.
 * Top-level and internal so a loopback test can run the exact production rewrite.
 */
internal fun smugMugCacheRewriteInterceptor(): Interceptor = Interceptor { chain ->
    val request = chain.request()
    val response = chain.proceed(request)
    val urlPath = request.url.encodedPath
    val isBypassedEndpoint = urlPath.contains("imagesearch") ||
        urlPath.contains("image!search") ||
        urlPath.contains("node!search") ||
        urlPath.contains("unlock") ||
        request.url.queryParameter("Text") != null
    if (request.method == "GET" && response.isSuccessful && !isBypassedEndpoint) {
        val cacheControl = response.header("Cache-Control")
        if (cacheControl == null || cacheControl.contains("no-store") || cacheControl.contains("no-cache") || cacheControl.contains("max-age=0")) {
            response.newBuilder()
                .header("Cache-Control", "public, max-age=300") // Cache for 5 minutes
                .build()
        } else {
            response
        }
    } else {
        response
    }
}

/**
 * The production OkHttp client, extracted unchanged from [AppModule.provideOkHttpClient] so a test can
 * run the exact interceptor chain, [okhttp3.Cache] and cookie jar in front of a loopback server
 * (phase 4 step 4-0). [isOnline] replaces the connectivity check; [dns] defaults to the system's.
 *
 * Cache policy (step 4-6): [cachePolicyInterceptor] is the one owner of "may a cached API response be
 * reused?". Offline it accepts any stale cached response (API calls, and thumbnail loads through
 * provideRetryingCallFactory); online it never reuses a response sent before the last session-cookie
 * change ([epoch], bumped by the cookie jar). Anything never fetched has nothing to serve and fails with
 * OkHttp's synthetic 504, which callers already handle (Room/UI fallbacks).
 */
internal fun buildSmugMugClient(
    cacheDir: File,
    isOnline: () -> Boolean,
    cookieJar: okhttp3.CookieJar,
    epoch: CredentialEpoch,
    loggingInterceptor: Interceptor,
    cacheSizeBytes: Long = 50 * 1024 * 1024L, // 50 MB
    dns: okhttp3.Dns = okhttp3.Dns.SYSTEM
): OkHttpClient {
    val headerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "SmugView-Android-App/1.0")
            .build()
        chain.proceed(request)
    }

    val cacheInterceptor = smugMugCacheRewriteInterceptor()
    val cache = okhttp3.Cache(cacheDir, cacheSizeBytes)

    return OkHttpClient.Builder()
        .cache(cache)
        .cookieJar(cookieJar)
        .dns(dns)
        .addInterceptor(cachePolicyInterceptor(isOnline, epoch))
        .addInterceptor(headerInterceptor)
        .addNetworkInterceptor(cacheInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        redactor: com.smugview.app.diag.Redactor
    ): OkHttpClient {
        // Never log full URLs/bodies in release: the URLs carry the SmugMug APIKey and gallery
        // Password query params, and bodies can contain private data.
        val loggingInterceptor = debugHttpLogger(
            redactor,
            level = if (com.smugview.app.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        ) { android.util.Log.d("OkHttp", it) }

        val epoch = CredentialEpoch()
        return buildSmugMugClient(
            cacheDir = File(context.cacheDir, "http_cache"),
            isOnline = { isNetworkAvailable(context) },
            cookieJar = SessionCookieJar(epoch),
            epoch = epoch,
            loggingInterceptor = loggingInterceptor
        )
    }

    /** Best-effort connectivity check used only to decide whether to force stale cache reuse. */
    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // Assume online if the check itself fails — don't force stale cache incorrectly.
        }
    }

    /**
     * Wraps [provideOkHttpClient] with non-blocking 429/5xx retry (see [RetryingCallFactory] for
     * why this has to be a [okhttp3.Call.Factory] decorator rather than an `Interceptor`) plus the
     * user-facing error Toast that used to live in an `errorInterceptor`. That reporting logic
     * moved here — instead of a chain-positioned `Interceptor` — specifically so it fires exactly
     * once per *logical* request (after retries settle), not once per retry attempt: a decorator
     * wrapping the whole client re-runs the full interceptor chain on every retry, so a plain
     * `Interceptor` in that chain would Toast on every intermediate 429/500, not just the final
     * outcome.
     *
     * Both Retrofit ([provideSmugMugApi]) and Coil (`SmugViewApp.newImageLoader`) are wired to
     * this instead of the raw [OkHttpClient] bean, so image loads keep the same retry/error
     * behavior as API calls — see the "Aggressive Image Loading Cache (Coil)" note in
     * `docs/SMUGMUG.md` ("routes all image file queries through the same ... retry policies used
     * by Retrofit").
     */
    @Provides
    @Singleton
    fun provideRetryingCallFactory(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context,
        telemetry: com.smugview.app.diag.HttpTelemetry
    ): okhttp3.Call.Factory {
        return RetryingCallFactory(
            delegate = okHttpClient,
            // One diagnostics line per logical request (replaces the old SmugMugApiError log): it
            // also covers IO failures, which no hook recorded before (R-50).
            actionIdProvider = com.smugview.app.diag.DiagContext::currentActionId,
            onComplete = telemetry::record,
            onFinalResponse = { request, response ->
                val isApiRequest = request.url.host == "api.smugmug.com" && request.url.encodedPath.contains("/api/v2/")
                if (isApiRequest && request.header("X-Ignore-Errors") != "true" &&
                    com.smugview.app.data.api.SmugMugErrorMapper.shouldShowToast(response)) {
                    val responseBodyContent = try {
                        response.peekBody(1024 * 1024L).string() // Peek up to 1MB
                    } catch (e: Exception) {
                        null
                    }
                    val friendlyMessage = com.smugview.app.data.api.SmugMugErrorMapper.getFriendlyMessage(
                        response.code, response.message, responseBodyContent
                    )

                    if (com.smugview.app.data.api.SmugMugErrorMapper.shouldShowToast(response.code)) {
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            android.widget.Toast.makeText(context, friendlyMessage, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    @Provides
    @Singleton
    fun provideSmugMugApi(callFactory: okhttp3.Call.Factory): SmugMugApi {
        return Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .callFactory(callFactory)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SmugMugApi::class.java)
    }

    /**
     * Injected so [com.smugview.app.ui.viewmodel.SmugViewModel]'s off-main-thread work can be
     * swapped for a [kotlinx.coroutines.test.TestDispatcher] in unit tests — `Dispatchers.setMain`
     * only redirects `Dispatchers.Main`, so a hardcoded `Dispatchers.Default` reference would keep
     * running on the real thread pool under `runTest` and could leak a coroutine past its test.
     */
    @Provides
    @Singleton
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "smugview_db"
        )
            .addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16)
            // v12 -> v13 .. v15 -> v16 have real migrations above, so users keep their data.
            //
            // Versions 1..11 (all prior production releases) shipped with
            // fallbackToDestructiveMigration(), so there is NO real migration path from
            // them. Registering an explicit destructive fallback *only* for those old
            // versions prevents the "no migration from N to 13 found" crash-on-upgrade,
            // while still preserving data for anyone already on v12+.
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
            .build()
    }

    @Provides
    @Singleton
    fun provideSyncStateStore(@ApplicationContext context: Context): com.smugview.app.data.repository.SyncStateStore =
        com.smugview.app.data.repository.PrefsSyncStateStore(context)

    @Provides
    @Singleton
    fun provideCollectionDao(database: AppDatabase): CollectionDao {
        return database.collectionDao()
    }

    @Provides
    @Singleton
    fun providePasswordStore(
        impl: com.smugview.app.data.security.EncryptedPasswordStore
    ): com.smugview.app.data.security.PasswordStore = impl

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideCastManager(impl: com.smugview.app.data.cast.DefaultCastManager): com.smugview.app.data.cast.CastManager {
        return impl
    }
}
