package com.smugview.app.scenario

import com.smugview.app.data.db.CachedNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Findings #19: SmugMug never sends "Inherited". School (P4BKB) has SecurityType None and
 * EffectiveSecurityType Password; it is protected by its root Family (2sDN5x). `needsPassword` must
 * say so from the lineage, not from the row's own access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PasswordRootNeedsPasswordTest {
    private val rig = ScenarioRig()
    @After fun tearDown() = rig.close()

    private val key = "k"
    private val school = CachedNode(
        nodeId = "P4BKB", parentNodeId = "2sDN5x", type = "Folder", title = "School", description = null,
        access = "None", passwordHint = null, uri = "/api/v2/node/P4BKB",
        childNodesUri = "/api/v2/node/P4BKB!children", albumUri = null
    )

    @Test fun `a sub-folder of a password folder needs a password with no session and nothing saved`() {
        assertTrue(runBlocking { rig.repository.unlocks.needsPassword(school, key) })
    }

    @Test fun `it stops needing one once the root has a saved password or a session`() {
        rig.passwords.savePassword("2sDN5x", "family-pw")
        assertFalse("a saved password is tried, not asked for", runBlocking { rig.repository.unlocks.needsPassword(school, key) })

        runBlocking { rig.repository.unlocks.ensureSession("P4BKB", key) }
        assertFalse("a live session needs none", runBlocking { rig.repository.unlocks.needsPassword(school, key) })
    }

    @Test fun `a public folder never needs one`() {
        val kentridge = school.copy(nodeId = "3BxbFF", parentNodeId = "4zqWw", title = "Kentridge", access = "None")
        assertFalse(runBlocking { rig.repository.unlocks.needsPassword(kentridge, key) })
    }
}
