package com.smugview.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val headerInterceptor = Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Accept", "application/json")
                .header("User-Agent", "SmugView-Android-App/1.0")
                .build()
            chain.proceed(request)
        }

        // Cache Interceptor: Force OkHttp to cache GET responses by replacing 'no-cache/no-store' with a 5-minute cache header.
        // IMPORTANT: Search endpoints are explicitly excluded — they must always hit the network for fresh results.
        val cacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val urlPath = request.url.encodedPath
            val isSearchEndpoint = urlPath.contains("imagesearch") ||
                urlPath.contains("image!search") ||
                urlPath.contains("node!search") ||
                request.url.queryParameter("Text") != null // Any request with a Text query param is a search
            if (request.method == "GET" && response.isSuccessful && !isSearchEndpoint) {
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

        val retryInterceptor = Interceptor { chain ->
            var request = chain.request()
            var response = chain.proceed(request)
            var tryCount = 0
            val maxLimit = 3
            var delayMs = 1000L

            // Retry for server failures or rate limiting (429, 5xx)
            while (!response.isSuccessful && (response.code == 429 || response.code in 500..599) && tryCount < maxLimit) {
                tryCount++
                response.close()
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException(e)
                }
                delayMs *= 2 // Exponential backoff
                response = chain.proceed(request)
            }
            response
        }

        val cookieJar = object : okhttp3.CookieJar {
            private val cookieStore = HashMap<String, MutableMap<String, okhttp3.Cookie>>()

            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                val hostCookies = cookieStore.getOrPut(url.host) { HashMap() }
                for (cookie in cookies) {
                    hostCookies[cookie.name] = cookie
                }
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                val hostCookies = cookieStore[url.host] ?: return emptyList()
                val now = System.currentTimeMillis()
                return hostCookies.values.filter { it.expiresAt > now }
            }
        }

        val cacheSize = 50 * 1024 * 1024L // 50 MB
        val cache = okhttp3.Cache(File(context.cacheDir, "http_cache"), cacheSize)

        return OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(cookieJar)
            .addInterceptor(headerInterceptor)
            .addNetworkInterceptor(cacheInterceptor)
            .addInterceptor(loggingInterceptor)
            .addInterceptor(retryInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideSmugMugApi(okHttpClient: OkHttpClient): SmugMugApi {
        return Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SmugMugApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "smugview_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun provideCollectionDao(database: AppDatabase): CollectionDao {
        return database.collectionDao()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}
