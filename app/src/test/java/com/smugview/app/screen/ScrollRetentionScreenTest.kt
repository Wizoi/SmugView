package com.smugview.app.screen

import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.smugview.app.data.repository.FakeSmugMugServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The biggest on-screen node that scrolls on [key]: the viewer's pager, or the gallery grid, never a chip row or a sheet. */
private fun biggestNode(compose: SemanticsNodeInteractionsProvider, key: SemanticsPropertyKey<ScrollAxisRange>) =
    compose.onAllNodes(SemanticsMatcher.keyIsDefined(key)).fetchSemanticsNodes().maxByOrNull { it.size.width.toLong() * it.size.height }

private fun biggestScroller(compose: SemanticsNodeInteractionsProvider, key: SemanticsPropertyKey<ScrollAxisRange>): Float? =
    biggestNode(compose, key)?.config?.get(key)?.value?.invoke()

private fun biggestScrollerNode(compose: SemanticsNodeInteractionsProvider, key: SemanticsPropertyKey<ScrollAxisRange>) =
    compose.onNode(SemanticsMatcher("the biggest scroller") { it.id == biggestNode(compose, key)?.id })

/** The current page (plus the fraction of a swipe in progress) of the viewer's pager, or null when there is none on screen. */
internal fun pagerValue(compose: SemanticsNodeInteractionsProvider): Float? =
    biggestScroller(compose, SemanticsProperties.HorizontalScrollAxisRange)

/** How far the gallery grid is scrolled, in items (a lazy grid reports `firstVisibleItemIndex + fraction`), or null with no grid. */
internal fun gridValue(compose: SemanticsNodeInteractionsProvider): Float? =
    biggestScroller(compose, SemanticsProperties.VerticalScrollAxisRange)

/**
 * Step 6-7 (design 3.5, R-48): positions survive.
 *
 *  - the grid scrolled to photo 40, a photo opened and Back: the grid is where it was (the old grid jumped to the top, because its
 *    "scroll to top when the sort or filter changes" effect also ran on every return);
 *  - the viewer opened on photo 5 and swiped to photo 6 while page 2 of the gallery is still streaming in: it stays on photo 6
 *    (the old viewer scrolled back to photo 5 every time the list grew).
 *
 * Fixture F: FfHCms is 150 photos in two pages (100 + 50) under the password folder Family -> School.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScrollRetentionScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private fun open() {
        screens.rig.passwords.savePassword("2sDN5x", "family-pw")
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
    }

    @Test fun `the grid is where it was after a photo is opened and Back is pressed`() {
        open()
        screens.setGalleryContent("FfHCms")
        screens.waitUntil(message = "the whole gallery loaded") { screens.viewModel.albumState.value?.complete == true }
        screens.waitUntil(message = "the grid is on screen") { gridValue(compose) != null }

        biggestScrollerNode(compose, SemanticsProperties.VerticalScrollAxisRange).performScrollToIndex(40)
        screens.settle()
        val before = gridValue(compose)!!
        assertTrue("the grid scrolled to about photo 40: $before", before >= 39f)

        screens.openViewer("FfHCms", "FfHCmsi045")
        screens.waitUntil(message = "the viewer is open") { screens.currentRoute == ScreenRig.VIEWER && pagerValue(compose) != null }
        compose.runOnUiThread { screens.navController!!.popBackStack() }
        screens.waitUntil(message = "the grid is back") { screens.currentRoute == ScreenRig.GRID && gridValue(compose) != null }

        assertTrue("the grid is still near photo 40, not at the top: ${gridValue(compose)}", gridValue(compose)!! >= 39f)
    }

    /** The pager's value once it has stopped moving (a swipe animates over several frames). */
    private fun settledPager(): Float {
        var last = Float.NaN
        screens.waitUntil(message = "the pager stopped moving") {
            val now = pagerValue(compose) ?: Float.NaN
            (now == last).also { last = now }
        }
        return last
    }

    // The pager's semantics value is its scroll offset in pixels (page * page size), so the size of one page is measured
    // here by swiping one page, and "photo 5" is then 4 pages.
    @Test fun `the viewer stays on the photo the user swiped to while page 2 streams in`() {
        val page2 = screens.server.hold("start=101")
        open()
        screens.setGalleryContent("FfHCms", startImageKey = "FfHCmsi005")

        screens.waitUntil(message = "page 1 arrived and the viewer left the first photo") {
            (screens.viewModel.albumState.value?.photos?.size ?: 0) >= 100 && (pagerValue(compose) ?: 0f) > 0f
        }
        assertTrue("page 2 is still held", screens.viewModel.albumState.value?.complete == false)
        val onPhoto5 = settledPager()

        biggestScrollerNode(compose, SemanticsProperties.HorizontalScrollAxisRange).performTouchInput { swipeLeft() }
        screens.waitUntil(message = "the viewer moved on") { (pagerValue(compose) ?: 0f) > onPhoto5 }
        val onPhoto6 = settledPager()
        assertEquals("the viewer was on photo 5 (4 pages in) and is on photo 6 (5 pages in)", 4f, onPhoto5 / (onPhoto6 - onPhoto5), 0.01f)

        page2.release()
        screens.waitUntil(message = "page 2 arrived") { screens.viewModel.albumState.value?.photos?.size == 150 }
        screens.settle()

        assertEquals("the viewer must stay on photo 6", onPhoto6, settledPager(), 0.5f)
    }
}
