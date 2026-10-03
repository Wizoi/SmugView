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

/** The background run over real Room: unlock first, check, announce, and only then remember. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewGalleryRunnerTest {
    private lateinit var db: AppDatabase
    private val listings = mutableMapOf<String, List<ListedGallery>>()
    private val events = mutableListOf<String>()
    private var announceResult = true
    private var announceFails = setOf<String>()
    private var unlockFails = setOf<String>()
    private lateinit var runner: NewGalleryRunner

    private fun g(id: String) = ListedGallery(id, "Gallery $id", "https://x.smugmug.com/Family/School/$id", "/Family/School/$id", albumKey = "key$id")

    @Before fun setUp() {
        db = TestDb.inMemory()
        val check = NewGalleryCheck(db.notifyDao()) { events += "list:$it"; listings.getValue(it) }
        runner = NewGalleryRunner(
            db.notifyDao(), check,
            unlock = { events += "unlock:$it"; if (it in unlockFails) error("unlock broke") },
            announcer = { site, galleries ->
                events += "announce:$site:${galleries.map { it.nodeId }}"
                if (site in announceFails) error("notify broke")
                announceResult
            }
        )
    }

    @After fun tearDown() { db.close() }

    private fun follow(vararg sites: String) = runBlocking { sites.forEach { db.notifyDao().follow(it) } }
    private fun runAll() = runBlocking { runner.runAll() }

    @Test fun `the session is unlocked before the listing, then a new gallery is announced and remembered`() {
        listings["a"] = listOf(g("n1"))
        follow("a"); runAll()
        listings["a"] = listOf(g("n2"), g("n1"))
        events.clear()
        runAll()
        assertEquals(listOf("unlock:a", "list:a", "announce:a:[n2]"), events)
        events.clear()
        runAll()
        assertEquals("told once", listOf("unlock:a", "list:a"), events)
    }

    @Test fun `when the notification cannot be posted the gallery is not marked, and is announced again next run`() {
        listings["a"] = listOf(g("n1"))
        follow("a"); runAll()
        listings["a"] = listOf(g("n2"), g("n1"))
        announceResult = false
        runAll()
        announceResult = true
        events.clear()
        runAll()
        assertTrue(events.contains("announce:a:[n2]"))
    }

    @Test fun `a failed unlock still checks the public galleries`() {
        listings["a"] = listOf(g("n1"))
        follow("a"); runAll()
        listings["a"] = listOf(g("n2"), g("n1"))
        unlockFails = setOf("a")
        events.clear()
        runAll()
        assertTrue(events.contains("announce:a:[n2]"))
    }

    @Test fun `one site that breaks does not stop the next`() {
        listings["a"] = listOf(g("n1")); listings["b"] = listOf(g("m1"))
        follow("a", "b"); runAll()
        listings["a"] = listOf(g("n2"), g("n1")); listings["b"] = listOf(g("m2"), g("m1"))
        announceFails = setOf("a")
        events.clear()
        runAll()
        assertTrue(events.contains("announce:b:[m2]"))
        announceFails = emptySet()
        events.clear()
        runAll()
        assertTrue("a's gallery was never marked, so it is announced now", events.contains("announce:a:[n2]"))
    }

    @Test fun `a site that is not followed is neither unlocked nor listed`() {
        listings["a"] = listOf(g("n1"))
        runAll()
        assertEquals(emptyList<String>(), events)
    }
}
