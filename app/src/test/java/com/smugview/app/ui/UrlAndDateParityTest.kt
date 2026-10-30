package com.smugview.app.ui

import android.net.Uri
import com.smugview.app.ui.detail.formatToLocalTime
import com.smugview.app.util.UrlParts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** The common URL and date helpers must give what `android.net.Uri` and `java.time` gave the screens before they moved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UrlAndDateParityTest {
    @Test fun `encode matches Uri encode`() {
        for (text in listOf("Summer/2024 & friends? 100%", "plain", "café 日本", "a+b=c;d,e:f@g", "-_.!~*'()", "", "emoji 😀")) {
            assertEquals(text, Uri.encode(text), UrlParts.encode(text))
        }
    }

    @Test fun `withQueryParameter matches the Uri builder`() {
        val key = "KEY123"
        for (url in listOf(
            "https://photos.smugmug.com/video.mp4",
            "https://photos.smugmug.com/video.mp4?x=1",
            "https://photos.smugmug.com/video.mp4?x=1&APIKey=OTHER",
            "https://photos.smugmug.com/video.mp4#frag",
            "https://photos.smugmug.com/video.mp4?x=1#frag"
        )) {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon()
            if (uri.getQueryParameter("APIKey") == null) builder.appendQueryParameter("APIKey", key)
            assertEquals(url, builder.build().toString(), UrlParts.withQueryParameter(url, "APIKey", key))
        }
    }

    @Test fun `dates keep their two-line format in the device zone`() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            assertEquals("Jul 06, 2026\n12:18 AM", formatToLocalTime("2026:07:06 00:18:44"))
            assertEquals("Jul 05, 2026\n05:18 PM", formatToLocalTime("2026-07-06T00:18:44Z"))
            assertEquals("Jul 06, 2026\n12:18 AM", formatToLocalTime("2026-07-06T00:18:44"))
            assertEquals("not a date", formatToLocalTime("not a date"))
            assertEquals("06/07/2026\n10Z".replace("Z", ""), formatToLocalTime("06/07/2026T10Z"))
            assertEquals("Unknown Date", formatToLocalTime(null))
        } finally {
            TimeZone.setDefault(old)
        }
    }
}
