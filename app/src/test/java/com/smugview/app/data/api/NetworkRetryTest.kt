package com.smugview.app.data.api

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class NetworkRetryTest {

    // Copy of retryInterceptor logic from AppModule
    private val retryInterceptor = Interceptor { chain ->
        var request = chain.request()
        var response = chain.proceed(request)
        var tryCount = 0
        val maxLimit = 5
        var delayMs = 500L

        while (!response.isSuccessful && (response.code == 429 || response.code in 500..599) && tryCount < maxLimit) {
            tryCount++
            var sleepTimeMs = delayMs
            if (response.code == 429) {
                val retryAfterHeader = response.header("Retry-After") ?: response.header("retry-after")
                val retryAfterSeconds = retryAfterHeader?.toLongOrNull()
                if (retryAfterSeconds != null) {
                    sleepTimeMs = retryAfterSeconds * 1000L
                }
            }

            response.close()
            try {
                // Sleep only if non-zero
                if (sleepTimeMs > 0) {
                    Thread.sleep(sleepTimeMs)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
            delayMs *= 2
            response = chain.proceed(request)
        }
        response
    }

    @Test
    fun testRetryInterceptorOn429Success() {
        val requestCount = AtomicInteger(0)
        
        // Mock server interceptor
        val mockServerInterceptor = Interceptor { chain ->
            val count = requestCount.incrementAndGet()
            if (count < 3) {
                // Return 429 for the first two requests with Retry-After: 0
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "0")
                    .body("Rate limit exceeded".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            } else {
                // Return 200 for the third request
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("Success Data".toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(retryInterceptor)
            .addInterceptor(mockServerInterceptor)
            .build()

        val request = Request.Builder()
            .url("https://api.smugmug.com/api/v2/test")
            .build()

        val response = client.newCall(request).execute()
        
        assertEquals(200, response.code)
        assertEquals("Success Data", response.body?.string())
        assertEquals(3, requestCount.get()) // 1 initial request + 2 retries
    }

    @Test
    fun testRetryInterceptorExceedsMaxLimit() {
        val requestCount = AtomicInteger(0)
        
        // Mock server interceptor that always returns 429
        val mockServerInterceptor = Interceptor { chain ->
            requestCount.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "0")
                .body("Rate limit exceeded".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(retryInterceptor)
            .addInterceptor(mockServerInterceptor)
            .build()

        val request = Request.Builder()
            .url("https://api.smugmug.com/api/v2/test")
            .build()

        val response = client.newCall(request).execute()
        
        assertEquals(429, response.code)
        assertEquals(6, requestCount.get()) // 1 initial request + 5 retries (max limit)
    }
}
