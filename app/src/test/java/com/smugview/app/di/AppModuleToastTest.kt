package com.smugview.app.di

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.smugview.app.diag.DiagLog
import com.smugview.app.diag.HttpTelemetry
import com.smugview.app.diag.Redactor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 6 step 6-6 (design 3.4, owner decision Q2): the app-wide error toast is gone. A failed API call is
 * shown by the screen that asked for it (6-3 to 6-7 gave each one a state), so a background sync that gets
 * a final 429 or 503 must not pop a toast over whatever the owner is looking at. The failure still reaches
 * telemetry and `report.txt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppModuleToastTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val telemetry = HttpTelemetry(DiagLog.NOOP)

    private fun answering(code: Int): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .header("Retry-After", "0").body("{}".toResponseBody(null)).build()
    }.build()

    /** One logical call to the API through the production factory; returns the final code. */
    private fun call(code: Int, path: String): Int {
        val factory: Call.Factory = AppModule.provideRetryingCallFactory(answering(code), telemetry)
        val latch = CountDownLatch(1)
        var got = -1
        factory.newCall(Request.Builder().url("https://api.smugmug.com/api/v2/$path").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { latch.countDown() }
            override fun onResponse(call: Call, response: Response) { got = response.code; response.close(); latch.countDown() }
        })
        assertTrue("the call did not finish", latch.await(30, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        return got
    }

    @Test fun `a final 429 on user albums posts no toast`() {
        assertEquals(429, call(429, "user/idzifamily!albums"))
        assertEquals("no toast", 0, ShadowToast.shownToastCount())
    }

    @Test fun `a final 503 posts no toast`() {
        assertEquals(503, call(503, "node/LCdk7F!children"))
        assertEquals("no toast", 0, ShadowToast.shownToastCount())
    }
}
