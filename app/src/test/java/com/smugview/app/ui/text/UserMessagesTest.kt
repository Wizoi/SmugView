package com.smugview.app.ui.text

import com.smugview.app.data.repository.AlbumLockedException
import com.smugview.app.data.repository.TransientReason
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Phase 6-3 (design 3.1, 5): every failure becomes one Problem, and every Problem has a heading, a body that
 * names the cause and a way out. No text carries an exception's own message.
 */
class UserMessagesTest {
    private fun http(code: Int, networkResponse: Boolean = true): HttpException {
        val req = Request.Builder().url("https://api.smugmug.com/api/v2/node/zz9!children").build()
        fun raw() = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("RAW-MESSAGE")
        val rawResponse = if (networkResponse) raw().networkResponse(raw().build()).build() else raw().build()
        return HttpException(retrofit2.Response.error<Any>("".toResponseBody(null), rawResponse))
    }

    private class Case(
        val name: String,
        val error: Throwable?,
        val expected: Problem,
        val underLockedRoot: Boolean = false
    )

    private val gallery = Subject.Gallery

    private val cases = listOf(
        Case("synthetic 504", http(504, networkResponse = false), Problem.OfflineNothingSaved(gallery)),
        Case("UnknownHost", UnknownHostException("api.smugmug.com"), Problem.OfflineNothingSaved(gallery)),
        Case("Connect", ConnectException("failed to connect"), Problem.OfflineNothingSaved(gallery)),
        Case("NoRouteToHost", NoRouteToHostException("no route"), Problem.OfflineNothingSaved(gallery)),
        Case("SocketTimeout", SocketTimeoutException("timeout"), Problem.Slow(gallery)),
        Case("429", http(429), Problem.RateLimited(gallery)),
        Case("500", http(500), Problem.SmugMugTrouble(gallery, 500)),
        Case("503", http(503), Problem.SmugMugTrouble(gallery, 503)),
        Case("real 504", http(504), Problem.SmugMugTrouble(gallery, 504)),
        Case("404 public", http(404), Problem.Gone(gallery)),
        Case("404 under a locked root", http(404), Problem.Locked(gallery), underLockedRoot = true),
        Case("401 under a locked root", http(401), Problem.Locked(gallery), underLockedRoot = true),
        Case("locked, no pending reason", AlbumLockedException("FfHCms"), Problem.Locked(gallery)),
        Case("locked, offline", AlbumLockedException("FfHCms", TransientReason.Offline), Problem.LockedPending(gallery, TransientReason.Offline)),
        Case("locked, busy", AlbumLockedException("FfHCms", TransientReason.Busy), Problem.LockedPending(gallery, TransientReason.Busy)),
        Case("418", http(418), Problem.Unexpected(gallery, "418")),
        Case("IllegalState", IllegalStateException("secret internal detail"), Problem.Unexpected(gallery, "IllegalState")),
        Case("null", null, Problem.Unexpected(gallery, "unknown"))
    )

    @Test fun everyFailureBecomesItsKind() {
        for (c in cases) {
            assertEquals(c.name, c.expected, Problem.from(c.error, gallery, c.underLockedRoot))
        }
    }

    @Test fun everyKindHasAHeadingABodyAndAWayOut_withoutRawText() {
        val raw = listOf("RAW-MESSAGE", "timeout", "secret internal detail", "api.smugmug.com", "failed to connect")
        for (c in cases) {
            val p = Problem.from(c.error, gallery, c.underLockedRoot)
            val heading = UserMessages.heading(p)
            val body = UserMessages.body(p)
            assertTrue("${c.name}: heading", heading.isNotBlank())
            assertTrue("${c.name}: body", body.isNotBlank())
            for (text in listOf(heading, body, UserMessages.shortCause(p), UserMessages.line(p))) {
                assertFalse("${c.name}: '$text' says Exception", text.contains("Exception"))
                assertFalse("${c.name}: '$text' says HTTP", text.contains("HTTP"))
                assertFalse("${c.name}: '$text' repeats a raw message", raw.any { text.contains(it) })
            }
            // The way out: the body says what to tap or do (the 4-8 texts say it in their own words).
            val saysTheWayOut = body.contains("Try again") || body.contains("Go back") ||
                body.contains("needs its password") || p is Problem.LockedPending
            assertTrue("${c.name}: no way out in '$body'", saysTheWayOut)
            assertTrue(UserMessages.primaryAction(p).label.isNotBlank())
        }
    }

    @Test fun theTextsAreExactlyDesignSection5() {
        val g = Subject.Gallery
        assertEquals("You're offline", UserMessages.heading(Problem.OfflineNothingSaved(g)))
        assertEquals(
            "This gallery hasn't been opened on this phone yet, so there's nothing saved to show. Connect to the internet and tap Try again.",
            UserMessages.body(Problem.OfflineNothingSaved(g))
        )
        assertEquals(
            "This folder hasn't been opened on this phone yet, so there's nothing saved to show. Connect to the internet and tap Try again.",
            UserMessages.body(Problem.OfflineNothingSaved(Subject.Folder))
        )
        assertEquals(
            "This photo hasn't been opened on this phone yet, so there's nothing saved to show. Connect to the internet and tap Try again.",
            UserMessages.body(Problem.OfflineNothingSaved(Subject.Photo))
        )
        assertEquals(
            "idzifamily hasn't been opened on this phone recently, so there's nothing saved to show. Connect to the internet and tap Try again.",
            UserMessages.body(Problem.OfflineNothingSaved(Subject.Site), siteName = "idzifamily")
        )
        assertEquals("No answer from SmugMug", UserMessages.heading(Problem.Slow(g)))
        assertEquals("SmugMug didn't answer in time. The connection may be weak. Tap Try again.", UserMessages.body(Problem.Slow(g)))
        assertEquals("SmugMug asked the app to slow down", UserMessages.heading(Problem.RateLimited(g)))
        assertEquals("Too many requests in a short time. Wait a minute, then tap Try again.", UserMessages.body(Problem.RateLimited(g)))
        assertEquals("SmugMug is having trouble", UserMessages.heading(Problem.SmugMugTrouble(g, 503)))
        assertEquals(
            "SmugMug answered with error 503. This is on SmugMug's side. Wait a few minutes, then tap Try again.",
            UserMessages.body(Problem.SmugMugTrouble(g, 503))
        )
        assertEquals("Not found on SmugMug", UserMessages.heading(Problem.Gone(g)))
        assertEquals(
            "This gallery may have been deleted, moved, or made private. Go back and refresh the folder.",
            UserMessages.body(Problem.Gone(g))
        )
        assertEquals("Password needed", UserMessages.heading(Problem.Locked(g)))
        assertEquals("This gallery needs its password.", UserMessages.body(Problem.Locked(g)))
        assertEquals("This folder needs its password.", UserMessages.body(Problem.Locked(Subject.Folder)))
        assertEquals("Couldn't load this gallery", UserMessages.heading(Problem.Unexpected(g, "418")))
        assertEquals(
            "SmugMug answered with error 418. Tap Try again. If it keeps happening, the diagnostics report has the details.",
            UserMessages.body(Problem.Unexpected(g, "418"))
        )
    }

    @Test fun theSavedPasswordTexts_areThe4_8TextsUnchanged() {
        val offline = Problem.LockedPending(gallery, TransientReason.Offline)
        val busy = Problem.LockedPending(gallery, TransientReason.Busy)
        assertEquals(AlbumLockedException.OFFLINE_MESSAGE, UserMessages.body(offline))
        assertEquals(AlbumLockedException.BUSY_MESSAGE, UserMessages.body(busy))
        assertEquals("You're offline", UserMessages.heading(offline))
        assertEquals("SmugMug is having trouble", UserMessages.heading(busy))
    }

    @Test fun shortCauses_andThePartialBanner() {
        assertEquals("You went offline.", UserMessages.shortCause(Problem.OfflineNothingSaved(gallery)))
        assertEquals("SmugMug didn't answer.", UserMessages.shortCause(Problem.Slow(gallery)))
        assertEquals("SmugMug asked the app to slow down.", UserMessages.shortCause(Problem.RateLimited(gallery)))
        assertEquals("SmugMug is having trouble.", UserMessages.shortCause(Problem.SmugMugTrouble(gallery, 503)))
        assertEquals("Error 418.", UserMessages.shortCause(Problem.Unexpected(gallery, "418")))
        assertEquals(
            "Showing 100 of 150 photos. SmugMug is having trouble.",
            UserMessages.partial(100, 150, Problem.SmugMugTrouble(gallery, 503))
        )
    }

    @Test fun theWayOut_perKind() {
        assertEquals(ProblemAction.TryAgain, UserMessages.primaryAction(Problem.Slow(gallery)))
        assertEquals(ProblemAction.TryAgain, UserMessages.primaryAction(Problem.OfflineNothingSaved(gallery)))
        assertEquals(ProblemAction.GoBack, UserMessages.primaryAction(Problem.Gone(gallery)))
        assertEquals(ProblemAction.EnterPassword, UserMessages.primaryAction(Problem.Locked(gallery)))
        assertEquals("Try again", ProblemAction.TryAgain.label)
        assertEquals("Go back", ProblemAction.GoBack.label)
        assertEquals("Enter password", ProblemAction.EnterPassword.label)
    }

    @Test fun otherIOExceptions_areOffline_likeIsOffline() {
        assertEquals(Problem.OfflineNothingSaved(gallery), Problem.from(IOException("reset"), gallery))
    }

    @Test fun homeFeaturedRow_saysEmptyOnlyWhenTheRequestSucceeded() {
        val site = Subject.Site
        assertEquals("This site has no public galleries.", UserMessages.homeAlbums(null))
        assertEquals(
            "You're offline. Galleries show here when you're back online.",
            UserMessages.homeAlbums(Problem.OfflineNothingSaved(site))
        )
        assertEquals(
            "Couldn't load the galleries. SmugMug is having trouble.",
            UserMessages.homeAlbums(Problem.SmugMugTrouble(site, 503))
        )
        assertEquals(
            "Couldn't load the galleries. SmugMug asked the app to slow down.",
            UserMessages.homeAlbums(Problem.RateLimited(site))
        )
    }

    @Test fun aSiteThatIsGone_namesTheSite_andSaysWhereToGo() {
        val gone = Problem.Gone(Subject.Site)
        assertEquals("Not found on SmugMug", UserMessages.heading(gone))
        assertEquals(
            "Nobody may have been deleted, moved, or made private. Go back and pick another site.",
            UserMessages.body(gone, "Nobody")
        )
        assertTrue(UserMessages.body(gone).startsWith("This site may have been deleted"))
    }

    @Test fun searchAndTagAndPasswordCheckTexts_areExactlyDesignSection5() {
        assertEquals("Nothing on idzifamily matches “zzqx”.", UserMessages.searchNoMatch("idzifamily", "zzqx"))
        assertEquals(
            "Couldn't search photos. You went offline. Galleries and folders below come from this phone.",
            UserMessages.searchPhotosFailed(Problem.OfflineNothingSaved(Subject.Search))
        )
        assertEquals(
            "Couldn't search photos. SmugMug is having trouble. Galleries and folders below come from this phone.",
            UserMessages.searchPhotosFailed(Problem.SmugMugTrouble(Subject.Search, 503))
        )
        assertEquals("Couldn't load the tags. SmugMug asked the app to slow down.", UserMessages.tagsFailed(Problem.RateLimited(Subject.Tags)))
        assertEquals("Couldn't check the password. SmugMug didn't answer.", UserMessages.passwordCheckFailed(Problem.Slow(Subject.Gallery)))
        assertEquals("Couldn't check the password. Error 418.", UserMessages.passwordCheckFailed(Problem.Unexpected(Subject.Gallery, "418")))
    }

    @Test fun downloadFailed_namesTheCause_andNeverTheExceptionText() {
        assertEquals("Couldn't save the photo. You went offline.", UserMessages.downloadFailed(Problem.from(UnknownHostException("RAW-MESSAGE"), Subject.Photo)))
        assertEquals("Couldn't save the photo. Error 418.", UserMessages.downloadFailed(Problem.Unexpected(Subject.Photo, "418")))
        assertFalse(UserMessages.downloadFailed(Problem.from(IOException("RAW-MESSAGE"), Subject.Photo)).contains("RAW-MESSAGE"))
    }

    @Test fun forSubject_changesTheNounOnly() {
        val p = Problem.OfflineNothingSaved(Subject.Gallery).forSubject(Subject.Photo)
        assertEquals(Problem.OfflineNothingSaved(Subject.Photo), p)
        assertEquals(Problem.SmugMugTrouble(Subject.Photo, 503), Problem.SmugMugTrouble(Subject.Gallery, 503).forSubject(Subject.Photo))
        assertEquals(Problem.Gone(Subject.Photo), Problem.Gone(Subject.Site).forSubject(Subject.Photo))
        assertTrue(UserMessages.body(p).startsWith("This photo hasn't been opened on this phone yet"))
    }

    @Test fun albumLockedException_userText_isTheLockText_notTheMessage() {
        assertEquals(AlbumLockedException.MESSAGE, AlbumLockedException("k").userText)
        assertEquals(AlbumLockedException.OFFLINE_MESSAGE, AlbumLockedException("k", TransientReason.Offline).userText)
        assertEquals(AlbumLockedException.BUSY_MESSAGE, AlbumLockedException("k", TransientReason.Busy).userText)
    }
}
