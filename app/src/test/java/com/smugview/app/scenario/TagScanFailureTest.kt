package com.smugview.app.scenario

import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.viewmodel.SearchScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * Step 6-6 (design 3.4): a failed tag scan used to write "Failed to fetch top keywords: {exception text}" into
 * `scanProgress`, which the tab only shows while scanning, so the owner saw an empty tag cloud and no reason. It
 * is now a [Problem] the tab says ("Couldn't load the tags. {cause}") with Try again; a later scan clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TagScanFailureTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val school = SearchScope("School", nodeId = "P4BKB", nodeUri = "/api/v2/node/P4BKB")

    /** Opens the site and waits until the hub's own topkeywords call is done, so a planned failure meets the scan's call. */
    private fun openSite() {
        rig.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        awaitUntil("the hub asked for the top keywords") { rig.server.requests.any { it.contains("!topkeywords") } }
        Thread.sleep(300)
    }

    private fun scan() = rig.viewModel.triggerTagScopeScan(school)

    @Test fun `a 503 on the top keywords is a problem, not text in the progress line`() {
        openSite()
        rig.server.respondWith("topkeywords", 503, times = 1)
        scan()

        try { awaitUntil("the problem is set") { rig.viewModel.scanProblem.value != null } } catch (e: AssertionError) { throw AssertionError("${e.message}; progress='${rig.viewModel.scanProgress.value}' scanning=${rig.viewModel.isScanningTags.value} requests=${synchronized(rig.server.requestLog) { rig.server.requestLog.map { it.url.encodedPath } }}", e) }
        assertEquals(Problem.SmugMugTrouble(Subject.Tags, 503), rig.viewModel.scanProblem.value)
        awaitUntil("scanning stopped") { !rig.viewModel.isScanningTags.value }
        assertTrue("no exception text in the progress line: '${rig.viewModel.scanProgress.value}'", !rig.viewModel.scanProgress.value.contains("HTTP"))

        // Try again (the server is healthy now): the problem is cleared and the tags arrive.
        scan()
        awaitUntil("the tags arrived") { rig.viewModel.allScopeTags.value.isNotEmpty() }
        assertNull(rig.viewModel.scanProblem.value)
    }

    @Test fun `no network on the top keywords is offline`() {
        openSite()
        rig.server.failWith = { target -> if (target.contains("topkeywords")) UnknownHostException("offline") else null }
        scan()

        try { awaitUntil("the problem is set") { rig.viewModel.scanProblem.value != null } } catch (e: AssertionError) { throw AssertionError("${e.message}; progress='${rig.viewModel.scanProgress.value}' scanning=${rig.viewModel.isScanningTags.value} requests=${synchronized(rig.server.requestLog) { rig.server.requestLog.map { it.url.encodedPath } }}", e) }
        assertEquals(Problem.OfflineNothingSaved(Subject.Tags), rig.viewModel.scanProblem.value)
    }
}
