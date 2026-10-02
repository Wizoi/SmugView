package com.smugview.app.journey

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import com.smugview.app.ui.text.UserMessages
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.getOrNull
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.screen.ScreenRig
import com.smugview.app.screen.gridValue
import com.smugview.app.screen.pagerValue
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6-18 (design 8.1): the seven journeys, over the app's own `NavHost` ([com.smugview.app.SmugViewNavigation]) and screens
 * on the fake server. Each is an acceptance test over the Phase 1-6 fixes; the screen and scenario tests own the single rules.
 * Fixture F: Family 2sDN5x (password) -> School P4BKB -> gallery LCdk7F / FfHCms (150 photos), public Kentridge 3BxbFF -> N74KSK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class JourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.Dispatchers.Main)
    private lateinit var screens: ScreenRig

    @Before fun setUp() { screens = ScreenRig(compose) }
    @After fun tearDown() = screens.close()

    private val vm get() = screens.viewModel
    private val server get() = screens.server

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun awaitText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) =
        screens.waitUntil(timeoutMs, "\"$text\" on screen") { shown(text, substring) }
    private fun tap(text: String) = compose.onNodeWithText(text).performClick()
    private fun tapDescribed(description: String) = compose.onNodeWithContentDescription(description).performClick()
    private fun lit() = runBlocking { screens.rig.dao.getNodesWithActiveUpdates(FakeSmugMugServer.SITE_A).first() }

    /** A photo tile of the gallery grid: the fixture's photos have no title, so a tile's description is empty. */
    private val photoTile = SemanticsMatcher("a photo tile") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() == "" &&
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
    }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun launch(site: String = FakeSmugMugServer.SITE_A) {
        vm.selectSite(site)
        screens.setAppContent()
        screens.awaitSiteQuiet()
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

    @Test fun `J1 Family with its password, School, a gallery, a photo and Back three times`() {
        val page2 = server.hold("start=101")
        val page1 = server.hold("FfHCms!images")
        launch()
        awaitText("Family")
        awaitUntilLit("the launch crawl lit the gallery")

        tap("Family")
        awaitText("Please enter password", substring = true)
        compose.onNodeWithText("Password").performTextInput("family-pw")
        tap("Unlock")
        awaitText("School")
        tap("School")
        awaitText("New School Year")
        tap("New School Year")

        // Q3: the dot is cleared by the first page of photos, not by the tap.
        screens.waitUntil(message = "page 1 was asked for") { server.requestsTo("FfHCms!images").isNotEmpty() }
        screens.waitUntil(message = "the grid is on screen, waiting for page 1") { vm.albumState.value?.albumKey == "FfHCms" }
        Thread.sleep(300)
        assertTrue("the dot is still lit while page 1 is on its way", "LCdk7F" in lit())
        page1.release()
        screens.waitUntil(message = "page 1 shown and the dot out") { (vm.albumState.value?.photos?.size ?: 0) >= 100 && "LCdk7F" !in lit() }

        // The grid keeps its place across a photo and Back; the viewer stays on the photo swiped to while page 2 streams in.
        screens.waitUntil(message = "the grid is on screen") { gridValue(compose) != null }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onFirst().performScrollToIndex(40)
        screens.settle()
        val gridBefore = gridValue(compose)!!
        assertTrue("the grid is at about photo 40: $gridBefore", gridBefore >= 39f)

        val rootBox = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = compose.onAllNodes(photoTile).fetchSemanticsNodes().indexOfLast { it.boundsInRoot.top >= rootBox.top && it.boundsInRoot.bottom <= rootBox.bottom }
        assertTrue("a photo tile is on screen", visible >= 0)
        compose.onAllNodes(photoTile)[visible].performClick()
               screens.waitUntil(message = "the viewer is open") { pagerValue(compose) != null }
        val first = settledPager()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).onFirst()
            .performTouchInput { swipeLeft() }
        screens.waitUntil(message = "the viewer moved on") { (pagerValue(compose) ?: 0f) > first }
        val swipedTo = settledPager()
        page2.release()
        screens.waitUntil(message = "page 2 arrived") { vm.albumState.value?.photos?.size == 150 }
        screens.settle()
        assertEquals("the viewer stays on the swiped photo", swipedTo, settledPager(), 0.5f)

        systemBack()
        screens.waitUntil(message = "the grid is back") { gridValue(compose) != null }
        assertTrue("the grid kept its place: ${gridValue(compose)}", gridValue(compose)!! >= 39f)

        systemBack()
        awaitText("New School Year")
        systemBack()
        awaitText("School")
        assertTrue("Family's listing, not School's", !shown("New School Year"))
    }

    /** The bottom bar tab named [label] (the same word is also an icon and a title elsewhere). */
    private fun tapTab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()

    private fun toasts() = org.robolectric.shadows.ShadowToast.shownToastCount()

    @Test fun `J2 offline launch with the profile cached, a gallery never opened, then Try again online`() {
        vm.selectSite(FakeSmugMugServer.SITE_A)
        screens.awaitSiteQuiet()
        server.failWith = { java.net.UnknownHostException("offline") }
        screens.setAppContent()
        awaitText("Kentridge")
        tap("Kentridge")
        awaitText("Public Gallery")
        tap("Public Gallery")

        awaitText(UserMessages.OFFLINE_HEADING)
        assertEquals("no toast says it", 0, toasts())
        assertTrue("and no photos", vm.albumState.value?.photos.isNullOrEmpty())

        server.failWith = null
        tap(UserMessages.BUTTON_TRY_AGAIN)
        screens.waitUntil(10_000, "the gallery's photos") { (vm.albumState.value?.photos?.size ?: 0) > 0 }
        assertTrue("the offline heading is gone", !shown(UserMessages.OFFLINE_HEADING))
        assertEquals(0, toasts())
    }

    @Test fun `J3 search a word, open a photo, jump to its gallery, search a word nobody has, search offline`() {
        server.imageSearchTotal = 3
        launch()
        tapTab("Search")
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("kentridge")
        field.performImeAction()
        awaitText("Photos (3)")
        screens.waitUntil(message = "photo tiles") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() }
        val rootBox = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = compose.onAllNodes(photoTile).fetchSemanticsNodes().indexOfLast { it.boundsInRoot.top >= rootBox.top && it.boundsInRoot.bottom <= rootBox.bottom }
        assertTrue("a result photo is on screen", visible >= 0)
        compose.onAllNodes(photoTile)[visible].performClick()
        screens.waitUntil(message = "the viewer") { pagerValue(compose) != null }
        screens.settle()
        compose.onRoot().performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        awaitText("Jump to Gallery")
        tap("Jump to Gallery")
        screens.waitUntil(10_000, "the photo's gallery opened") { vm.albumState.value?.albumKey == "N74KSK" }
        assertEquals("no toast says the gallery was not found", 0, toasts())
        screens.waitUntil(message = "the gallery's photos") { (vm.albumState.value?.photos?.size ?: 0) > 0 }

        // Back to the search; a word nobody has says so, with the site's name.
        systemBack(); systemBack()
        screens.waitUntil(message = "the search screen again") { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        server.imageSearchTotal = 0
        search("zzqx")
        awaitText(UserMessages.searchNoMatch((vm.activeUserProfile.value?.name?.takeIf { it.isNotBlank() } ?: FakeSmugMugServer.SITE_A), "zzqx"))

        // Offline, a word the index knows: no crash, the failed photo search is named and the galleries come from the phone.
        server.failWith = { if (it.contains("image!search")) java.net.UnknownHostException("offline") else null }
        search("Public")
        awaitText("Couldn't search photos.", substring = true)
        tap("Galleries (1)")
        awaitText("Public Gallery")
    }

    /** The viewer open on the last fully visible photo tile; its controls shown (a tap, then the double-tap window passes). */
    private fun openLastVisiblePhotoWithControls(controls: String = "Jump to Gallery", describedAs: Boolean = false) {
        screens.waitUntil(message = "photo tiles") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() }
        val rootBox = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = compose.onAllNodes(photoTile).fetchSemanticsNodes().indexOfLast { it.boundsInRoot.top >= rootBox.top && it.boundsInRoot.bottom <= rootBox.bottom }
        assertTrue("a photo is on screen", visible >= 0)
        compose.onAllNodes(photoTile)[visible].performClick()
        screens.waitUntil(message = "the viewer") { pagerValue(compose) != null }
        screens.settle()
        compose.onRoot().performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (describedAs) screens.waitUntil(message = "\"$controls\" button") { compose.onAllNodesWithContentDescription(controls).fetchSemanticsNodes().isNotEmpty() } else awaitText(controls)
    }

    @Test fun `J4 pick a tag, see its photos, open one, Back keeps the tag, the photo jumps to its gallery`() {
        server.imageSearchTotal = 3
        server.nodeTopKeywords["4zqWw"] = listOf("kentridge", "graduation")
        launch()
        tapTab("Tags")
        awaitText("kentridge")
        tap("kentridge")
        tapDescribed("Search Images for Tags")
        screens.waitUntil(message = "the tag's photos are in the view model") { vm.keywordPhotosTotal.value > 0 }
        openLastVisiblePhotoWithControls()

        systemBack()
        screens.waitUntil(message = "back on the tag's photos") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() && pagerValue(compose) == null }
        systemBack()
        awaitText("Active Tag Filters:")
        assertEquals("the tag is still selected", setOf("kentridge"), vm.selectedTags.value.keys)

        tapDescribed("Search Images for Tags")
        openLastVisiblePhotoWithControls()
        tap("Jump to Gallery")
        screens.waitUntil(10_000, "the photo's gallery opened") { vm.albumState.value?.albumKey == "N74KSK" }
        assertEquals("no toast says the gallery was not found", 0, toasts())
    }

    /** The share dialog lays out at zero size under Robolectric, so a touch misses its buttons; the click is invoked on the node. */
    private fun invokeClick(text: String) = compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it.invoke() }

    /** Taps "Share link" in the open share dialog and returns the web address in the text the share sheet was given, then closes the dialog. */
    private fun sharedLinkText(): String {
        awaitText(UserMessages.SHARE_CAPTION)
        invokeClick(UserMessages.SHARE_LINK)
        screens.waitUntil(message = "the share sheet was opened") { org.robolectric.Shadows.shadowOf(compose.activity).peekNextStartedActivity() != null || org.robolectric.Shadows.shadowOf(screens.rig.app).peekNextStartedActivity() != null }
        val chooser = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity ?: org.robolectric.Shadows.shadowOf(screens.rig.app).nextStartedActivity
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        val text = send.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        invokeClick("Done")
        screens.waitUntil(message = "the share dialog closed") { !shown(UserMessages.SHARE_CAPTION) }
        return text.substringAfter(": ")
    }

    private fun assertIsAWebPage(url: String, photoPage: Boolean = false) {
        assertTrue("a web page address, not a file: $url", com.smugview.app.share.ShareContent.isShareable(url))
        assertTrue("no photos.smugmug.com in $url", !url.contains("photos.smugmug.com"))
        if (photoPage) assertTrue("a photo page ends with /i-{key}: $url", Regex(".*/i-[^/]+$").matches(url))
    }

    @Test fun `J6 share a gallery, a public photo and a photo of a password gallery`() {
        launch()
        awaitText("Kentridge"); tap("Kentridge")
        awaitText("Public Gallery"); tap("Public Gallery")
        screens.waitUntil(message = "the gallery's photos") { (vm.albumState.value?.photos?.size ?: 0) > 0 }
        tapDescribed("Share Album")
        val galleryLink = sharedLinkText()
        assertIsAWebPage(galleryLink)

        openLastVisiblePhotoWithControls("Share Link", describedAs = true)
        tapDescribed("Share Link")
        val photoLink = sharedLinkText()
        assertIsAWebPage(photoLink, photoPage = true)
        assertTrue("the photo's page is under its gallery's: $photoLink under $galleryLink", photoLink.startsWith(galleryLink.trimEnd('/')))

        // A photo of a password gallery (Family -> School -> New School Year).
        systemBack(); screens.waitUntil(message = "the grid") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() }
        systemBack(); awaitText("Public Gallery")
        tapTab("Home")
        awaitText("Family"); tap("Family")
        awaitText("Please enter password", substring = true)
        compose.onNodeWithText("Password").performTextInput("family-pw")
        tap("Unlock")
        awaitText("School"); tap("School")
        awaitText("New School Year"); tap("New School Year")
        screens.waitUntil(message = "page 1") { (vm.albumState.value?.photos?.size ?: 0) >= 100 }
        tapDescribed("Share Album")
        assertIsAWebPage(sharedLinkText())
        openLastVisiblePhotoWithControls("Share Link", describedAs = true)
        tapDescribed("Share Link")
        assertIsAWebPage(sharedLinkText(), photoPage = true)
    }

    private val sql get() = screens.rig.db.openHelper.writableDatabase
    private fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun `J5 save a photo, see it saved, open it offline, delete the collection`() {
        val now = System.currentTimeMillis()
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Trip', '${FakeSmugMugServer.SITE_A}', $now)")
        launch()
        awaitText("Kentridge"); tap("Kentridge")
        awaitText("Public Gallery"); tap("Public Gallery")
        openLastVisiblePhotoWithControls("Save", describedAs = true)

        // Open the dialog and Cancel: nothing is written.
        tapDescribed("Save")
        awaitText("Trip")
        tap("Cancel")
        screens.settle()
        assertEquals("Cancel wrote no bookmark", 0, count("collection_bookmarks"))
        assertEquals("Cancel wrote no saved copy", 0, count("offline_files"))

        // Save: the bookmark is written, and the saved copy is wanted.
        tapDescribed("Save")
        awaitText("Trip"); tap("Trip")
        tap("Save")
        screens.waitUntil(message = "the bookmark written") { count("collection_bookmarks") == 1 }
        assertEquals("one saved copy is wanted", 1, count("offline_files"))
        screens.waitUntil(message = "the dialog closed") { !shown("Cancel") }
        val imageKey = sql.query("SELECT imageKey FROM offline_files").use { it.moveToFirst(); it.getString(0) }

        val cdn = com.smugview.app.data.repository.FakeCdn()
        try {
            val downloader = com.smugview.app.data.offline.OfflineDownloader(
                screens.rig.offlineStore, screens.rig.repository, cdn.clientOver(okhttp3.OkHttpClient()),
                apiKey = { "test-key" }, settings = screens.rig.settings
            )
            runBlocking { downloader.runPass(com.smugview.app.data.offline.NetworkClass.ANY) }
            val file = java.io.File(screens.rig.offlineFilesDir, com.smugview.app.data.offline.OfflineStore.relPathOf(imageKey, "jpg"))
            assertTrue("the original is on the phone: $imageKey cdn=${cdn.requests}", file.isFile)

            // Collections: the row says Saved.
            systemBack(); screens.waitUntil(message = "the gallery grid") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() }
            systemBack()
            screens.waitUntil(message = "the tab bar is back") { compose.onAllNodes(hasText("Collections") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).fetchSemanticsNodes().isNotEmpty() }
            tapTab("Collections")
            awaitText("Trip"); tap("Trip")
            awaitText("Public Gallery"); tap("Public Gallery")
            awaitText(com.smugview.app.data.offline.OfflineMessages.SAVED)

            // Offline, the saved photo still opens, from the file.
            server.failWith = { java.net.UnknownHostException("offline") }
            screens.imageRequests.clear(); val toastsBefore = toasts()
            compose.onNodeWithText("Photo $imageKey").performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 12f)) }
            screens.waitUntil(10_000, "the viewer opened offline") { pagerValue(compose) != null }
            screens.waitUntil(message = "the picture came from the saved file") { screens.imageRequests.any { it.contains(screens.rig.offlineFilesDir.name) } }
            assertEquals("opening a saved photo offline shows no toast", toastsBefore, toasts())
            systemBack()
            screens.waitUntil(message = "back on the collection") { pagerValue(compose) == null }
            server.failWith = null

            // Delete the collection: it asks, then the file goes.
            tapDescribed("Delete Collection")
            awaitText("Delete “Trip”?", substring = true)
            assertTrue("nothing deleted while asking", file.isFile)
            tap("Delete")
            screens.waitUntil(message = "the file is gone") { !file.exists() }
            assertEquals(0, count("collection_bookmarks"))
        } finally {
            cdn.close()
        }
    }

    private fun search(query: String) {
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement(query)
        field.performImeAction()
    }

    /** Moves the Robolectric clock, which the real cast manager's delays (connect, scans, slideshow) run on. */
    private fun advance(ms: Long) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        screens.settle()
    }

    @Test fun `J7 cast a gallery, Stop inside a second, pick again, run the slideshow and Stop`() {
        val tv = com.smugview.app.data.cast.CastDevice("192.168.1.60", "Living room TV", "192.168.1.60", com.smugview.app.data.cast.CastType.AMAZON)
        // A device whose cast makes no network call (the screen-link kind): a Roku's would POST to a real address from the main thread here.
        val io = com.smugview.app.data.cast.FakeCastIo().apply { devices = listOf(tv); dialSupported = false }
        val caught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
        try {
            screens.close()
            lateinit var manager: com.smugview.app.data.cast.DefaultCastManager
            screens = ScreenRig(compose, castFactory = { app ->
                com.smugview.app.data.cast.DefaultCastManager(app, android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher(), io).also { manager = it }
            })
            launch()
            awaitText("Kentridge"); tap("Kentridge")
            awaitText("Public Gallery"); tap("Public Gallery")
            screens.waitUntil(message = "the gallery grid") { compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty() }

            fun pick() {
                tapDescribed("Cast screen. Tap to discover devices.")
                advance(3_100) // one scan
                awaitText("Living room TV")
                tap("Living room TV")
                awaitText("Stop Casting")
            }

            // 1. Pick and press Stop before the connect's second is up.
            pick()
            assertEquals(com.smugview.app.data.cast.ConnectionState.CONNECTING, manager.activeDevice.value?.state)
            tap("Stop Casting")
            advance(3_000)
            assertEquals(null, manager.activeDevice.value)
            assertTrue("not casting", !manager.isCasting.value)
            assertTrue("no device shown as connected", manager.discoveredDevices.value.none { it.state == com.smugview.app.data.cast.ConnectionState.CONNECTED })
            assertTrue("the screen link was never bound: ${io.serverStarts}", io.serverStarts.isEmpty())
            assertTrue("back on the gallery", compose.onAllNodes(photoTile).fetchSemanticsNodes().isNotEmpty())

            // 2. Pick again, let it connect: the slideshow runs.
            pick()
            advance(1_100)
            assertEquals(com.smugview.app.data.cast.ConnectionState.CONNECTED, manager.activeDevice.value?.state)
            assertTrue("the slideshow is playing", manager.isSlideshowPlaying.value)
            advance(500)
            assertTrue("a photo is on the screen: ${manager.currentImageUri.value}", manager.currentImageUri.value?.contains("/X3/") == true)
            assertTrue("the controls offer Pause", shownDescribed("Pause slideshow"))
            tap("Stop Casting")
            advance(500)
            assertEquals(null, manager.activeDevice.value)
            assertTrue("the slideshow stopped", !manager.isSlideshowPlaying.value)
            assertEquals(null, manager.currentImageUri.value)

            // 3. Nothing keeps scanning, and nothing threw.
            advance(120_000)
            assertEquals("every scan socket was closed", io.socketsOpened.get(), io.socketsClosed.get())
            assertTrue("no scan is in flight", !io.scanSocketOpen)
            val scans = io.scans.get()
            advance(120_000)
            assertEquals("no scan after discovery stopped", scans, io.scans.get())
            assertTrue("still disconnected", manager.activeDevice.value == null && !manager.isCasting.value)
            assertTrue("nothing was thrown: $caught", caught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun shownDescribed(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun awaitUntilLit(message: String) =
        screens.waitUntil(15_000, message) { "LCdk7F" in lit() }
}
