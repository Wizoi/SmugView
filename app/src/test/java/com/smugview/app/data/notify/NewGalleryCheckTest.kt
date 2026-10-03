package com.smugview.app.data.notify

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * New-gallery notifications, stage 1 (docs/design/new-gallery-notification.md). The listing is the newest page of
 * `user!albums` by date added; this checks the NodeID diff and what is remembered, on a real in-memory Room database.
 * Real shapes: NodeID is not the AlbumKey, and the new galleries sit two folders deep under a password folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewGalleryCheckTest {
    private lateinit var db: AppDatabase
    private var listing: List<ListedGallery> = emptyList()
    private var failure: Throwable? = null
    private var calls = 0
    private lateinit var check: NewGalleryCheck

    private fun g(nodeId: String, name: String = "Gallery $nodeId") =
        ListedGallery(nodeId, name, "https://idzifamily.smugmug.com/Family/School/$nodeId", "/Family/School/$nodeId")

    @Before fun setUp() {
        db = TestDb.inMemory()
        check = NewGalleryCheck(db.notifyDao()) { calls++; failure?.let { throw it }; listing }
    }

    @After fun tearDown() { db.close() }

    private fun follow(nickname: String = "idzifamily") = runBlocking { db.notifyDao().follow(nickname) }
    private fun run(nickname: String = "idzifamily") = runBlocking { check.run(nickname) }

    @Test fun `the first listing of a followed site is recorded and announces nothing`() {
        listing = (1..100).map { g("n$it") }
        follow()
        assertEquals(CheckResult.Baselined(100), run())
        assertEquals(CheckResult.NothingNew, run())
    }

    @Test fun `a gallery that appears after the baseline is found, newest first, and only it`() {
        listing = listOf(g("n2"), g("n1"))
        follow(); run()
        listing = listOf(g("n4"), g("n3"), g("n2"), g("n1"))
        assertEquals(CheckResult.Found(listOf(g("n4"), g("n3"))), run())
    }

    @Test fun `a gallery is not told as known until markNotified, so a crash before the notification repeats it`() {
        listing = listOf(g("n1"))
        follow(); run()
        listing = listOf(g("n2"), g("n1"))
        assertEquals(CheckResult.Found(listOf(g("n2"))), run())
        assertEquals("not marked yet", CheckResult.Found(listOf(g("n2"))), run())
        runBlocking { check.markNotified("idzifamily", listOf(g("n2"))) }
        assertEquals(CheckResult.NothingNew, run())
    }

    @Test fun `an updated gallery moving up the list is not new`() {
        listing = listOf(g("n3"), g("n2"), g("n1"))
        follow(); run()
        listing = listOf(g("n1"), g("n3"), g("n2"))
        assertEquals(CheckResult.NothingNew, run())
    }

    @Test fun `an offline or rate limited listing changes nothing and the next success still finds the gallery`() {
        listing = listOf(g("n1"))
        follow(); run()
        failure = IOException("offline")
        assertEquals(CheckResult.Skipped, run())
        failure = retrofit2.HttpException(retrofit2.Response.error<Any>(429, okhttp3.ResponseBody.create(null, "")))
        assertEquals(CheckResult.Skipped, run())
        failure = null
        listing = listOf(g("n2"), g("n1"))
        assertEquals(CheckResult.Found(listOf(g("n2"))), run())
    }

    @Test fun `a failed first listing leaves the site unbaselined, not baselined on nothing`() {
        failure = IOException("offline")
        follow()
        assertEquals(CheckResult.Skipped, run())
        failure = null
        listing = listOf(g("n1"))
        assertEquals(CheckResult.Baselined(1), run())
    }

    @Test fun `an empty listing neither announces nor forgets`() {
        listing = listOf(g("n1"))
        follow(); run()
        listing = emptyList()
        assertEquals(CheckResult.NothingNew, run())
        listing = listOf(g("n1"))
        assertEquals(CheckResult.NothingNew, run())
    }

    @Test fun `a site that is not followed is not even asked`() {
        assertEquals(CheckResult.NotFollowed, run("someoneelse"))
        assertEquals(0, calls)
    }

    @Test fun `following again starts a new baseline, so galleries made while it was off are not announced`() {
        listing = listOf(g("n1"))
        follow(); run()
        runBlocking { db.notifyDao().unfollow("idzifamily") }
        listing = listOf(g("n2"), g("n1"))
        assertEquals(CheckResult.NotFollowed, run())
        follow()
        assertEquals(CheckResult.Baselined(2), run())
        assertEquals(CheckResult.NothingNew, run())
    }

    @Test fun `two followed sites keep separate known sets`() {
        listing = listOf(g("n1"))
        follow("idzifamily"); follow("other"); run("idzifamily")
        listing = listOf(g("n2"), g("n1"))
        assertEquals(CheckResult.Found(listOf(g("n2"))), run("idzifamily"))
        assertEquals("the other site has no baseline yet", CheckResult.Baselined(2), run("other"))
    }

    @Test fun `following lists enabled sites for the worker`() {
        follow("idzifamily"); follow("other")
        runBlocking { db.notifyDao().unfollow("other") }
        assertEquals(listOf("idzifamily"), runBlocking { db.notifyDao().enabledSites() }.map { it.nickname })
        assertTrue(runBlocking { db.notifyDao().site("other") } == null)
    }
}
