package com.smugview.app.data.repository

import com.smugview.app.scenario.ScenarioRig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 step 3-1 (design 3.5, R-10): a write is stamped with the nickname of the work that
 * issued it, not with whatever site the singleton repository points at when the write lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScopingRepoTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private fun nicknameOf(table: String, nodeId: String): String? =
        rig.db.openHelper.readableDatabase.query("SELECT nickname FROM $table WHERE nodeId = '$nodeId'").use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    @Test
    fun aLateWriteOfSiteA_afterTheActiveSiteChangedToB_isStampedWithSiteA() = runBlocking {
        rig.repository.setActiveNickname("idzifamily")
        val gate = rig.server.hold("node/P4BKB!children")

        val sync = async(Dispatchers.Default) { rig.repository.syncFolderTree("idzifamily", "4zqWw", "k") }
        assertTrue("School's listing reached the server", gate.awaitArrived())
        rig.repository.setActiveNickname("siteb")
        gate.release()
        sync.await()

        assertEquals("Family was written before the switch", "idzifamily", nicknameOf("cached_nodes", "2sDN5x"))
        assertEquals("cached_nodes row of the gallery", "idzifamily", nicknameOf("cached_nodes", "LCdk7F"))
        assertEquals("cached_albums row of the gallery", "idzifamily", nicknameOf("cached_albums", "LCdk7F"))
    }
}
