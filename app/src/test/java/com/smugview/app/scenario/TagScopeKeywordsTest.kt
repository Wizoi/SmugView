package com.smugview.app.scenario

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 step 4-1 (design Q6, P2, R-28): `user!topkeywords` is scoped by `NodeURI`; the app sent `NodeID`,
 * which the endpoint ignores, so a scoped tag scan answered with the whole-site list. The fake honours
 * `NodeURI` only (like the live API), so a scoped request must get the scope's list, not the site's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TagScopeKeywordsTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private fun keywords(nodeId: String?) = runBlocking {
        rig.repository.getUserTopKeywords("idzifamily", "test-key", nodeId = nodeId)
            .response.userTopKeywords?.keywords
    }

    @Test fun `a scoped request returns the keywords of the scope, not of the whole site`() {
        assertEquals(listOf("a", "b"), keywords("P4BKB"))
    }

    @Test fun `an unscoped request returns the whole-site keywords`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), keywords(null))
    }
}
