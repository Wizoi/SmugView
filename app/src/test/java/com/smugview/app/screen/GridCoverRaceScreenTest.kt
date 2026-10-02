package com.smugview.app.screen

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.scenario.readState
import com.smugview.app.ui.grid.PhotoGridScreen
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.GalleryFilterType
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Step 6-11b: the grid's cover-photo read must not index the paging list on a stale count.
 *
 * `PhotoGridScreen` looks the gallery's node up in Room (a suspend call) and then, if the node has no highlight image, takes the first
 * photo's thumbnail as the cover. The count it checked and the photo it read (`lazyPhotos[0]`) came from two different places: `itemCount`
 * is a Compose state, `get` reads the live paging presenter. A type filter that hides every photo empties the presenter first and
 * publishes the new snapshot a moment later, and the effect, resumed from Room, can land in between ("Index: 0, Size: 0" from
 * `PagePresenter.checkIndex`; it failed `GridStatesScreenTest` one full run in two).
 *
 * The window is a few microseconds wide, so this test makes it as wide as the test needs, deterministically: it holds the lookup
 * (a gate in the DAO, only for calls from `getNodeByAlbumKey`), runs the filter's paging update inside a mutable snapshot that is not
 * applied yet (so no other thread sees the new count: exactly "presenter emptied, snapshot not published"), then releases the lookup
 * from another thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class GridCoverRaceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val hold = CoverLookupHold()
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose, repositoryDao = { hold.wrap(it) }) }
    @After fun tearDown() { hold.release(); screens.close() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun `the cover read survives a type filter emptying the list while the node lookup is in flight`() {
        screens.rig.passwords.savePassword("2sDN5x", "family-pw")
        screens.viewModel.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        var probe: LazyPagingItems<AlbumImageData>? = null
        screens.setContent {
            // A second reader of the same shared paging flow, so the test can see when the presenter has taken the empty list.
            probe = screens.viewModel.photosFlow.collectAsLazyPagingItems()
            PhotoGridScreen(
                albumKey = "N74KSK", // 12 photos, none a video
                albumTitle = "A gallery",
                onNavigateToPhotoDetail = {},
                onBackClick = {},
                onNavigateToFolder = {},
                onNavigateToCastController = {},
                viewModel = screens.viewModel
            )
        }
        screens.waitUntil(message = "the gallery loaded") { screens.viewModel.albumState.value?.complete == true }
        screens.waitUntil(message = "12 photos on the grid") { readState { probe!!.itemSnapshotList.size } == 12 }
        // The effect has run for the empty list and for the 12 photos; the second is the one the filter races.
        screens.waitUntil(message = "the cover lookup for 12 photos is held") { hold.started.get() >= 2 }

        screens.viewModel.updateFilterType(GalleryFilterType.VIDEOS)
        val unpublished = Snapshot.takeMutableSnapshot()
        try {
            unpublished.enter {
                val deadline = System.currentTimeMillis() + 5_000
                while (probe!!.itemSnapshotList.size != 0) {
                    check(System.currentTimeMillis() < deadline) { "the filtered (empty) list never reached the presenter" }
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                // The grid's own presenter takes the same emission a message or two later.
                repeat(25) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }
            }
            // Other threads still see 12 photos while the presenters hold none. Resume the held lookup on one of them.
            thread(name = "cover-lookup-release") { hold.release() }.join()
            // The effect reads the list right after the lookup returns, on the thread Room resumed it on. Wait for every held lookup
            // (the cancelled ones of earlier runs of the effect included) to be over, twice, in case the lookup made a second query.
            repeat(2) {
                val deadline = System.currentTimeMillis() + 5_000
                while (hold.completed.get() < 1 || hold.finished.get() < hold.started.get()) {
                    check(System.currentTimeMillis() < deadline) { "the held lookup never finished" }
                    Thread.sleep(10)
                }
                Thread.sleep(150)
            }
        } finally {
            unpublished.apply().check()
            Snapshot.sendApplyNotifications()
        }

        screens.waitUntil(message = "the filtered-out message") { shown(UserMessages.FILTER_EMPTY) }
        assertTrue("an empty gallery is not a filtered-out one", !shown(UserMessages.EMPTY_GALLERY))
        assertEquals(GalleryFilterType.VIDEOS, screens.viewModel.filterType.value)
    }
}

/**
 * Holds every DAO node lookup made on behalf of `SmugViewModel.getNodeByAlbumKey` (found by the call stack at entry: the grid's
 * cover effect is its only caller) until [release].
 */
private class CoverLookupHold {
    private val gate = CompletableDeferred<Unit>()
    val started = AtomicInteger()
    /** Held lookups that are over (returned or cancelled), and those that returned a result. */
    val finished = AtomicInteger()
    val completed = AtomicInteger()

    fun release() { gate.complete(Unit) }

    fun wrap(real: CollectionDao): CollectionDao = object : CollectionDao by real {
        override suspend fun getNodeById(nodeId: String) = held { real.getNodeById(nodeId) }
        override suspend fun getNodeByIdOrKey(idOrKey: String) = held { real.getNodeByIdOrKey(idOrKey) }
    }

    private suspend fun <T> held(call: suspend () -> T): T {
        if (Throwable().stackTrace.none { it.methodName == "getNodeByAlbumKey" }) return call()
        started.incrementAndGet()
        try {
            gate.await()
            return call().also { completed.incrementAndGet() }
        } finally {
            finished.incrementAndGet()
        }
    }
}
