package com.smugview.app.scenario

import com.smugview.app.data.repository.SmugMugRepository.UnlockResult
import com.smugview.app.data.repository.SmugMugRepository.UnlockSummary
import com.smugview.app.data.repository.UnlockManager.Access
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3-6 (design 3.4, 4 "429 on !unlock", Q5): UnlockManager is the one owner of "is there a session
 * for this password root?". Fixture F: password folder 2sDN5x (Family) -> P4BKB (School) -> gallery
 * NodeID LCdk7F / AlbumKey FfHCms. The saved password is under the root's key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UnlockManagerTest {
    private var rig: ScenarioRig? = null
    private fun rig(retrying: Boolean = false) = ScenarioRig(retrying).also { rig = it }
    @After fun tearDown() { rig?.close() }

    private val key = "k"
    private val pw = "fake-pw-1"
    private fun unlockPosts(r: ScenarioRig) = r.server.requestsTo("!unlock")

    @Test fun `a 429 storm on unlock is one call and its retries, not one per caller`() {
        val r = rig(retrying = true)
        r.passwords.savePassword("2sDN5x", pw)
        // RetryingCallFactory: the first try plus 5 retries, so 6 throttled answers exhaust ONE call.
        r.server.respond429("!unlock", 6)
        val gate = r.server.hold("!unlock")
        val results = runBlocking {
            val calls = listOf("LCdk7F", "P4BKB", "2sDN5x").map {
                async(Dispatchers.Default) { r.repository.unlocks.ensureSession(it, key) }
            }
            // Each caller reads its lineage first; wait until all three are past that and the first
            // unlock request is held, so the other two have joined its flight (not started their own).
            assertTrue("an unlock request reached the fake", gate.awaitArrived())
            awaitUntil("three lineage reads") { r.server.requestsTo("!parents").size >= 3 }
            Thread.sleep(300)
            gate.release()
            calls.awaitAll()
        }
        assertEquals(List(3) { UnlockResult.Transient }, results)
        assertEquals("one call = 1 try + 5 retries, all throttled", 6, unlockPosts(r).size)
        assertEquals(pw, r.passwords.getPassword("2sDN5x"))
        assertEquals(Access.Saved, r.repository.unlocks.access.value["2sDN5x"])
    }

    @Test fun `a background 401 marks the root Invalid and keeps the saved password`() {
        val r = rig()
        r.passwords.savePassword("2sDN5x", pw)
        r.server.unlockCode = 401

        val result = runBlocking { r.repository.unlocks.ensureSession("LCdk7F", key) }

        assertEquals(UnlockResult.Rejected, result)
        assertEquals(Access.Invalid, r.repository.unlocks.access.value["2sDN5x"])
        assertEquals("a background path never deletes", pw, r.passwords.getPassword("2sDN5x"))
        // Asking again with the same rejected password costs no request.
        val before = unlockPosts(r).size
        assertEquals(UnlockResult.Rejected, runBlocking { r.repository.unlocks.ensureSession("P4BKB", key) })
        assertEquals(before, unlockPosts(r).size)
    }

    @Test fun `a different password is tried on an Invalid root and a success makes it Session`() {
        val r = rig()
        r.passwords.savePassword("2sDN5x", pw)
        r.server.unlockCode = 401
        runBlocking { r.repository.unlocks.ensureSession("2sDN5x", key) }
        r.server.unlockCode = 200

        val result = runBlocking { r.repository.unlocks.ensureSession("2sDN5x", key, "typed-pw") }

        assertEquals(UnlockResult.Success, result)
        assertEquals(Access.Session, r.repository.unlocks.access.value["2sDN5x"])
        assertEquals("saved password is untouched", pw, r.passwords.getPassword("2sDN5x"))
    }

    @Test fun `a Session needs no request, and the epoch counts new sessions only`() {
        val r = rig()
        r.passwords.savePassword("2sDN5x", pw)
        val u = r.repository.unlocks
        assertEquals(0, u.sessionEpoch.value)

        assertEquals(UnlockResult.Success, runBlocking { u.ensureSession("LCdk7F", key) })
        assertEquals(1, u.sessionEpoch.value)
        assertEquals(1, unlockPosts(r).size)

        assertEquals(UnlockResult.Success, runBlocking { u.ensureSession("P4BKB", key) })
        assertEquals(1, unlockPosts(r).size)
        assertEquals(1, u.sessionEpoch.value)
    }

    @Test fun `a read that got 401 while Session goes back to Saved and unlocks again`() {
        val r = rig()
        r.passwords.savePassword("2sDN5x", pw)
        val u = r.repository.unlocks
        runBlocking { u.ensureSession("2sDN5x", key) }
        assertEquals(Access.Session, u.access.value["2sDN5x"])

        val results = runBlocking {
            listOf("LCdk7F", "P4BKB").map { async(Dispatchers.Default) { u.reauthorize(it, key) } }.awaitAll()
        }

        assertEquals(List(2) { UnlockResult.Success }, results)
        assertEquals(Access.Session, u.access.value["2sDN5x"])
        val n = unlockPosts(r).size
        assertTrue("the first unlock plus one or two re-unlocks, not more: $n", n in 2..3)
    }

    @Test fun `a transient failure leaves the root Saved, not Invalid`() {
        val r = rig()
        r.passwords.savePassword("2sDN5x", pw)
        r.server.respondWith("!unlock", 500, 1)

        assertEquals(UnlockResult.Transient, runBlocking { r.repository.unlocks.ensureSession("LCdk7F", key) })

        assertEquals(Access.Saved, r.repository.unlocks.access.value["2sDN5x"])
        assertEquals(pw, r.passwords.getPassword("2sDN5x"))
    }

    @Test fun `passwordFor prefers the root key, falls back to legacy copies, and never writes`() {
        val r = rig()
        val u = r.repository.unlocks
        r.passwords.savePassword("LCdk7F", "legacy-copy")
        val legacyOnly = r.passwords.all()
        assertEquals("legacy copy is the fallback", "legacy-copy", runBlocking { u.passwordFor("LCdk7F", key) })
        assertEquals(legacyOnly, r.passwords.all())

        r.passwords.savePassword("2sDN5x", pw)
        val withRoot = r.passwords.all()
        assertEquals("root key wins", pw, runBlocking { u.passwordFor("LCdk7F", key) })
        assertEquals(pw, runBlocking { u.passwordFor("P4BKB", key) })
        assertEquals(withRoot, r.passwords.all())
        assertNull(runBlocking { u.passwordFor("sXQz4G", key) })
    }

    @Test fun `rootOf answers the password folder for anything under it`() {
        val r = rig()
        val u = r.repository.unlocks
        assertEquals("2sDN5x", runBlocking { u.rootOf("LCdk7F", key) })
        assertEquals("2sDN5x", runBlocking { u.rootOf("P4BKB", key) })
        assertEquals("2sDN5x", runBlocking { u.rootOf("2sDN5x", key) })
    }

    @Test fun `recordLaunch maps the launch unlock's results onto access`() {
        val r = rig()
        val u = r.repository.unlocks
        u.recordLaunch(
            UnlockSummary(
                roots = 3, ok = 1, rejected = 1, transient = 1,
                results = mapOf("2sDN5x" to UnlockResult.Success, "Wq8Rz3" to UnlockResult.Rejected, "LCdk7F" to UnlockResult.Transient)
            )
        )
        assertEquals(Access.Session, u.access.value["2sDN5x"])
        assertEquals(Access.Invalid, u.access.value["Wq8Rz3"])
        assertEquals(Access.Saved, u.access.value["LCdk7F"])
        assertEquals(1, u.sessionEpoch.value)
    }
}
