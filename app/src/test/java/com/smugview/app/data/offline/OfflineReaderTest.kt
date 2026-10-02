package com.smugview.app.data.offline

import android.net.Uri
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.OfflineGallery
import com.smugview.app.data.db.OfflineGalleryItem
import com.smugview.app.scenario.GridProbe
import com.smugview.app.scenario.ScenarioRig
import com.smugview.app.scenario.awaitUntil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Phase 5 step 5-9 (design 3, 4, Q1, Q2, Q3, Q8): what the screens read from the saved files.
 *
 * The real view model and repository over Room and the fake server on a real socket (the production client, so
 * "offline" is OkHttp's real synthetic 504). Topology as on `idzifamily`: gallery `FfHCms` (NodeID `LCdk7F`, so
 * AlbumKey != NodeID) in a password folder; its photos are the fake's `FfHCmsi001`... in listing order. Dates are
 * relative to now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineReaderTest {
    private lateinit var rig: ScenarioRig
    private val now = System.currentTimeMillis()
    private val site = "idzifamily"
    private val gallery = "FfHCms"
    private val vm get() = rig.viewModel
    private val sql get() = rig.db.openHelper.writableDatabase
    private val dao get() = rig.db.offlineDao()
    private val keys by lazy { rig.server.imageKeysOf(gallery) }

    @Before fun setUp() {
        rig = ScenarioRig(httpCache = true)
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '$site', $now)")
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '$site', $now)")
        sql.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (3, 'Empty', '$site', $now)")
    }

    @After fun tearDown() = rig.close()

    // ---- fixtures: the rows and files the offline pass leaves behind ----

    private fun taken() = Instant.ofEpochMilli(now).minus(Duration.ofDays(10)).toString()

    /** A DONE row with its file on disk (the bytes are a stand-in; nothing here decodes a picture). */
    private fun saved(key: String, bytes: Int = 1_000, format: String? = "JPG", album: String = gallery): File = runBlocking {
        val rel = OfflineStore.relPathOf(key, "jpg")
        val file = File(rig.offlineFilesDir, rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(bytes) { 7 }) }
        dao.insertFile(
            OfflineFile(
                fileKey = OfflineStore.fileKeyOf(key), imageKey = key, albumKey = album, nickname = site,
                expectedBytes = bytes.toLong(), title = "IMG_$key", thumbnailUrl = "https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg",
                format = format, dateTaken = taken(), state = OfflineStore.DONE, relPath = rel, bytes = bytes.toLong(),
                createdAt = now, updatedAt = now
            )
        )
        file
    }

    private fun row(key: String, state: String, failure: FailureReason? = null, retryable: Boolean = false, httpCode: Int? = null, expected: Long? = 2_000) = runBlocking {
        dao.insertFile(
            OfflineFile(
                fileKey = OfflineStore.fileKeyOf(key), imageKey = key, albumKey = gallery, nickname = site,
                expectedBytes = expected, title = "IMG_$key", state = state, failure = failure?.name,
                retryable = retryable, httpCode = httpCode, createdAt = now, updatedAt = now
            )
        )
    }

    private fun photoRow(key: String, collection: Int) = sql.execSQL(
        "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, localFilePath, dateTaken, keywords, isDownloaded) " +
            "VALUES ('$key', $collection, '$gallery', 'IMG_$key', 'https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg', " +
            "'https://photos.smugmug.com/photos/i-$key/0/D/i-$key-D.jpg', NULL, '${taken()}', NULL, 0)"
    )

    private fun keepGallery(collection: Long, listed: List<String>, wifiOnly: Boolean = true, state: String = OfflineStore.LISTED, album: String = gallery) = runBlocking {
        dao.insertGallery(OfflineGallery(collection, album, site, "Class photos", state, photoCount = listed.size, wifiOnly = wifiOnly))
        dao.insertGalleryItems(listed.mapIndexed { i, k -> OfflineGalleryItem(collection, album, k, i) })
    }

    // ---- Q2: a saved photo opens from its file ----

    /** Red when the placeholder carries no `localUri` (the viewer would go to the network for a photo that is on the phone). */
    @Test fun `a saved photo opens with its own file as localUri`() {
        val file = saved(keys[3])
        photoRow(keys[3], 1)
        rig.loopback!!.online = false

        vm.selectAlbum(gallery, targetImageKey = keys[3])

        awaitUntil("the saved photo shows") { GridProbe.keys(vm) == listOf(keys[3]) && vm.albumState.value?.loading == false }
        val photo = vm.albumState.value!!.photos.single()
        assertEquals(Uri.fromFile(file).toString(), photo.localUri)
        assertNull("a still never gets a videoUrl (N2)", photo.videoUrl)
        assertEquals("JPG", photo.format)
        assertNull("opening a saved photo offline is not an error", GridProbe.error(vm))
        assertEquals(OfflineMessages.offlineGallery(1), vm.albumNotice.value)
    }

    /** A photo of a kept gallery has no collection row at all: only the file row knows it. */
    @Test fun `a photo that only a kept gallery saved opens from its file too`() {
        val file = saved(keys[5])
        keepGallery(2, listOf(keys[5]))
        rig.loopback!!.online = false

        vm.selectAlbum(gallery, targetImageKey = keys[5])

        awaitUntil("the photo shows") { GridProbe.keys(vm).isNotEmpty() && vm.albumState.value?.loading == false }
        assertEquals(Uri.fromFile(file).toString(), vm.albumState.value!!.photos.first().localUri)
    }

    /** The reader never hands out a file that is not there (cleared storage, a half-written file). */
    @Test fun `a DONE row whose file is gone is not a local file`() = runBlocking {
        val file = saved(keys[1])
        assertEquals(file, rig.reader.localFile(keys[1]))
        file.delete()
        assertNull(rig.reader.localFile(keys[1]))
        assertNull(rig.reader.savedPhoto(keys[1]))
    }

    @Test fun `localFiles lists every saved photo and no unfinished one`() = runBlocking {
        val a = saved(keys[0]); val b = saved(keys[1])
        row(keys[2], OfflineStore.PENDING)
        row(keys[3], OfflineStore.FAILED, FailureReason.GONE)

        val files = rig.reader.localFiles().first()

        assertEquals(mapOf(keys[0] to a, keys[1] to b), files)
    }

    // ---- Q2: a kept gallery opens offline ----

    /** Red on the 4-8 behaviour: the grid shows the offline error and no photos. */
    @Test fun `a kept gallery opened offline lists its saved photos with the offline line`() {
        saved(keys[0]); saved(keys[1]); saved(keys[2])
        row(keys[3], OfflineStore.PENDING) // listed but not saved yet: not shown
        keepGallery(2, keys.take(4))
        rig.loopback!!.online = false

        vm.selectAlbum(gallery)

        awaitUntil("the saved photos show") { GridProbe.keys(vm).size == 3 && vm.albumState.value?.loading == false }
        assertEquals("listing order", keys.take(3), GridProbe.keys(vm))
        assertNull(GridProbe.error(vm))
        assertEquals(OfflineMessages.offlineGallery(3), vm.albumNotice.value)
        assertTrue(vm.albumState.value!!.photos.all { it.localUri != null && it.thumbnailUrl == it.localUri })
        assertEquals("not kept as a finished gallery: online it must load for real", false, GridProbe.complete(vm))
    }

    /** Pin: nothing saved, nothing kept: the offline error is unchanged. */
    @Test fun `a gallery with nothing saved still shows the offline error`() {
        rig.loopback!!.online = false

        vm.selectAlbum(gallery)

        awaitUntil("the error shows") { GridProbe.error(vm) != null }
        assertEquals(emptyList<String>(), GridProbe.keys(vm))
        assertNull(vm.albumNotice.value)
    }

    /** Pin: a 5xx is SmugMug answering, not the phone being offline: the saved copy is not a stand-in for it. */
    @Test fun `a server error on a kept gallery shows the error, not the saved photos`() {
        val open = "N74KSK" // the public gallery: no password prompt in front of its photos
        val key = rig.server.imageKeysOf(open)[0]
        saved(key, album = open); keepGallery(2, listOf(key), album = open)
        rig.server.respondWith("album/$open!images", 503, 50)

        vm.selectAlbum(open)

        awaitUntil("the error shows") { GridProbe.error(vm) != null }
        assertEquals(emptyList<String>(), GridProbe.keys(vm))
        assertNull(vm.albumNotice.value)
    }

    @Test fun `the saved photos of a gallery are not the saved photos of another`() = runBlocking {
        saved(keys[0]); keepGallery(2, listOf(keys[0]))

        assertEquals(listOf(keys[0]), rig.reader.savedGallery(gallery).map { it.row.imageKey })
        assertEquals(emptyList<String>(), rig.reader.savedGallery("N74KSK").map { it.row.imageKey })
    }

    // ---- rows: the state a collection row shows ----

    /** Red when no state is computed: each failed row says why, in the words of OfflineMessages. */
    @Test fun `a failed row says its own cause in the OfflineMessages words`() = runBlocking {
        val cases = listOf(
            Triple(FailureReason.GONE, null as Int?, false),
            Triple(FailureReason.FORBIDDEN, 403, false),
            Triple(FailureReason.DAMAGED, null, false),
            Triple(FailureReason.UNEXPECTED, 418, false),
            Triple(FailureReason.NO_SOURCE, null, false),
            Triple(FailureReason.OFFLINE, null, true),
            Triple(FailureReason.BUSY, 429, true),
            Triple(FailureReason.LOCKED, null, true)
        )
        cases.forEachIndexed { i, (reason, code, retryable) ->
            photoRow(keys[i], 1)
            row(keys[i], OfflineStore.FAILED, reason, retryable, code)
        }

        val rows = rig.reader.collection(1).first().rows

        cases.forEachIndexed { i, (reason, code, retryable) ->
            val state = rows.getValue(keys[i])
            assertEquals(RowState.Kind.FAILED, state.kind)
            assertEquals("text for $reason", OfflineMessages.forFailure(reason, code), state.text)
            assertEquals("auto-retry for $reason", retryable, state.retryable)
            assertEquals("try again for $reason", reason != FailureReason.GONE, state.canTryAgain)
            assertTrue("remove for $reason", state.canRemove)
        }
    }

    @Test fun `a full disk row carries the numbers`() = runBlocking {
        photoRow(keys[0], 1)
        row(keys[0], OfflineStore.FAILED, FailureReason.STORAGE_FULL, retryable = true, expected = 5_000_000)

        val state = rig.reader.collection(1).first().rows.getValue(keys[0])

        assertEquals(
            OfflineMessages.forFailure(FailureReason.STORAGE_FULL, null, rig.offlineStore.needBytes(5_000_000), rig.offlineStore.freeBytesNow()),
            state.text
        )
        assertTrue(state.text, state.text.contains("free"))
    }

    @Test fun `pending, downloading and done rows say so`() = runBlocking {
        photoRow(keys[0], 1); row(keys[0], OfflineStore.PENDING)
        photoRow(keys[1], 1); row(keys[1], OfflineStore.DOWNLOADING)
        photoRow(keys[2], 1); saved(keys[2])
        photoRow(keys[3], 1); saved(keys[3], format = "MP4")

        val rows = rig.reader.collection(1).first().rows

        assertEquals(OfflineMessages.SAVING, rows.getValue(keys[0]).text)
        assertEquals(OfflineMessages.SAVING, rows.getValue(keys[1]).text)
        assertEquals(RowState.Kind.SAVING, rows.getValue(keys[1]).kind)
        assertEquals(OfflineMessages.SAVED, rows.getValue(keys[2]).text)
        assertNull(rows.getValue(keys[2]).note)
        assertEquals("a video's saved copy is a still, and says so (Q7)", OfflineMessages.VIDEO_STILL, rows.getValue(keys[3]).note)
    }

    @Test fun `an Image bookmark row has a state too`() = runBlocking {
        sql.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                "VALUES (2, 'Image', '${keys[4]}', 'IMG', '$gallery', 'Class photos', NULL)"
        )
        row(keys[4], OfflineStore.PENDING)

        assertEquals(OfflineMessages.SAVING, rig.reader.collection(2).first().rows.getValue(keys[4]).text)
        assertTrue("another collection does not see it", rig.reader.collection(1).first().rows.isEmpty())
    }

    // ---- galleries: the summary under the Keep offline switch (Q1, Q3) ----

    @Test fun `a kept gallery that is saving shows its progress and the Wi-Fi rule`() = runBlocking {
        saved(keys[0]); saved(keys[1])
        row(keys[2], OfflineStore.PENDING, expected = 4_000_000)
        keepGallery(2, keys.take(3))

        val summary = rig.reader.collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.WAITING_WIFI, summary.kind)
        assertEquals(OfflineMessages.WAITING_FOR_WIFI, summary.text)
        assertEquals(OfflineMessages.waitingWifi(2, 3), summary.detail)
        assertEquals(OfflineMessages.useMobileData(4_000_000), summary.useMobileDataAction)
        assertFalse("the default is Wi-Fi only", summary.useMobileDataToo)
        assertEquals(2, summary.done); assertEquals(3, summary.total)
    }

    // 5-11: "Waiting for Wi-Fi" is only true when some other network exists. With none, say so (owner's rule: name the real cause).

    private fun readerWith(online: kotlinx.coroutines.flow.Flow<Boolean>) = OfflineReader(rig.db, rig.offlineStore, online)

    @Test fun `with no network at all a waiting gallery says no connection, not Waiting for Wi-Fi`() = runBlocking {
        saved(keys[0]); saved(keys[1])
        row(keys[2], OfflineStore.PENDING, expected = 4_000_000)
        keepGallery(2, keys.take(3))

        val summary = readerWith(kotlinx.coroutines.flow.flowOf(false)).collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.OFFLINE, summary.kind)
        assertEquals(OfflineMessages.NO_CONNECTION, summary.text)
        assertEquals(OfflineMessages.noConnectionProgress(2, 3), summary.detail)
        assertNull("there is no mobile network to offer", summary.useMobileDataAction)
        assertFalse(summary.text.contains("Wi-Fi"))
    }

    @Test fun `a gallery not listed yet, with no network, says no connection and offers no mobile data`() = runBlocking {
        keepGallery(2, emptyList(), state = OfflineStore.LIST_PENDING)

        val summary = readerWith(kotlinx.coroutines.flow.flowOf(false)).collection(2).first().galleries.getValue(gallery)

        assertEquals(OfflineMessages.NO_CONNECTION, summary.text)
        assertNull(summary.detail)
        assertNull(summary.useMobileDataAction)
    }

    @Test fun `with mobile data allowed and no network a gallery also says no connection, not Saving`() = runBlocking {
        saved(keys[0]); row(keys[1], OfflineStore.PENDING)
        keepGallery(2, keys.take(2), wifiOnly = false)

        val summary = readerWith(kotlinx.coroutines.flow.flowOf(false)).collection(2).first().galleries.getValue(gallery)

        assertEquals(OfflineMessages.NO_CONNECTION, summary.text)
    }

    // 5-11 (emulator, item 8): a pass stopped by the 1 GB floor left "Waiting for Wi-Fi" + "Use mobile data" on a Wi-Fi
    // phone, with the storage text only as a small second line. Mobile data cannot fix a full disk: say the real cause.
    @Test fun `a gallery stopped by a full disk says so and does not wait for Wi-Fi or offer mobile data`() = runBlocking {
        saved(keys[0])
        row(keys[1], OfflineStore.FAILED, FailureReason.STORAGE_FULL, retryable = true, expected = 5_000_000)
        row(keys[2], OfflineStore.PENDING, expected = 4_000_000)
        keepGallery(2, keys.take(3))

        val summary = readerWith(kotlinx.coroutines.flow.flowOf(true)).collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.FAILED, summary.kind)
        assertEquals(
            OfflineMessages.storage(rig.offlineStore.needBytes(9_000_000), rig.offlineStore.freeBytesNow()).substringBefore(" (needs"),
            summary.text.substringBefore(" (needs")
        )
        assertTrue(summary.text, summary.text.startsWith("Not enough space"))
        assertFalse(summary.text, summary.text.contains("Wi-Fi"))
        assertNull(summary.useMobileDataAction)
        assertNull("the storage text is the headline, not a second line", summary.detail)
    }

    @Test fun `on a mobile-only network the gallery still waits for Wi-Fi and offers mobile data`() = runBlocking {
        saved(keys[0]); row(keys[1], OfflineStore.PENDING, expected = 4_000_000)
        keepGallery(2, keys.take(2))

        val summary = readerWith(kotlinx.coroutines.flow.flowOf(true)).collection(2).first().galleries.getValue(gallery)

        assertEquals(OfflineMessages.WAITING_FOR_WIFI, summary.text)
        assertEquals(OfflineMessages.useMobileData(4_000_000), summary.useMobileDataAction)
    }

    @Test fun `the gallery line follows the network coming and going`() = runBlocking {
        saved(keys[0]); row(keys[1], OfflineStore.PENDING)
        keepGallery(2, keys.take(2))
        val net = kotlinx.coroutines.flow.MutableStateFlow(false)
        val flow = readerWith(net).collection(2)

        assertEquals(OfflineMessages.NO_CONNECTION, flow.first().galleries.getValue(gallery).text)
        net.value = true
        assertEquals(OfflineMessages.WAITING_FOR_WIFI, flow.first().galleries.getValue(gallery).text)
    }

    @Test fun `with mobile data allowed a gallery is saving, not waiting`() = runBlocking {
        saved(keys[0])
        row(keys[1], OfflineStore.PENDING)
        keepGallery(2, keys.take(2), wifiOnly = false)

        val summary = rig.reader.collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.SAVING, summary.kind)
        assertEquals(OfflineMessages.saving(1, 2), summary.text)
        assertTrue(summary.useMobileDataToo)
        assertNull(summary.useMobileDataAction)
    }

    @Test fun `a finished gallery says saved and names its size`() = runBlocking {
        saved(keys[0], bytes = 3_000_000); saved(keys[1], bytes = 1_000_000)
        keepGallery(2, keys.take(2))

        val summary = rig.reader.collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.SAVED, summary.kind)
        assertEquals(OfflineMessages.SAVED, summary.text)
        assertEquals(OfflineMessages.keepOffline(4_000_000), summary.keepLabel)
    }

    @Test fun `photos that cannot be saved are counted with their cause`() = runBlocking {
        saved(keys[0])
        row(keys[1], OfflineStore.FAILED, FailureReason.GONE)
        row(keys[2], OfflineStore.FAILED, FailureReason.GONE)
        keepGallery(2, keys.take(3))

        val summary = rig.reader.collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.FAILED, summary.kind)
        assertEquals("2 photos can't be saved: removed from SmugMug", summary.text)
    }

    @Test fun `a gallery that failed to list says why`() = runBlocking {
        dao.insertGallery(
            OfflineGallery(2, gallery, site, "Class photos", OfflineStore.FAILED, failure = FailureReason.LOCKED.name, retryable = true)
        )

        val summary = rig.reader.collection(2).first().galleries.getValue(gallery)

        assertEquals(GallerySummary.Kind.FAILED, summary.kind)
        assertEquals(OfflineMessages.LOCKED, summary.text)
    }

    // ---- Q8: deleting a collection ----

    /** The count and size are the database's: a file another collection holds is not lost, so it is not counted. */
    @Test fun `delete confirmation counts only what would be removed`() = runBlocking {
        saved(keys[0], bytes = 1_000); photoRow(keys[0], 1)                      // only Favorites
        saved(keys[1], bytes = 3_000); photoRow(keys[1], 1); photoRow(keys[1], 2) // shared with Trip
        row(keys[2], OfflineStore.PENDING); photoRow(keys[2], 1)                  // not saved yet
        saved(keys[3], bytes = 500); keepGallery(1, listOf(keys[3]))              // only Favorites, via a kept gallery

        val confirm = rig.reader.deleteConfirm(1, "Favorites")!!

        assertEquals(2, confirm.photos)
        assertEquals(1_500L, confirm.bytes)
        assertEquals(OfflineMessages.deleteConfirm("Favorites", 2, 1_500), confirm.text)
    }

    @Test fun `deleting a collection with nothing saved needs no confirmation`() = runBlocking {
        row(keys[0], OfflineStore.PENDING); photoRow(keys[0], 1)

        assertNull(rig.reader.deleteConfirm(1, "Favorites"))
        assertNull(rig.reader.deleteConfirm(3, "Empty"))
    }

    @Test fun `a collection whose saved photos are all shared needs no confirmation`() = runBlocking {
        saved(keys[1]); photoRow(keys[1], 1); photoRow(keys[1], 2)

        assertNull(rig.reader.deleteConfirm(1, "Favorites"))
    }

    // ---- Q10 (6-16): asking before the last reference to a saved copy goes ----

    private fun imageBookmark(key: String, collection: Int) = sql.execSQL(
        "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl, extraData) " +
            "VALUES ($collection, 'Image', '$key', 'IMG_$key', '$gallery', 'Class photos', NULL, NULL)"
    )

    @Test fun `unsaving the only reference to a saved copy asks, naming its size`() = runBlocking {
        saved(keys[0], bytes = 2_048); imageBookmark(keys[0], 1)

        val confirm = rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = false, dropsBookmark = true)!!

        assertEquals(1, confirm.photos)
        assertEquals(2_048L, confirm.bytes)
        assertEquals(OfflineMessages.removeImageConfirm("Sunset", "Favorites", 2_048), confirm.text)
    }

    @Test fun `a bookmark in another collection keeps the copy, so nothing is asked`() = runBlocking {
        saved(keys[0]); imageBookmark(keys[0], 1); imageBookmark(keys[0], 2)

        assertNull(rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = false, dropsBookmark = true))
    }

    @Test fun `unsaving from every collection that holds the photo asks, though each alone would not`() = runBlocking {
        saved(keys[0]); imageBookmark(keys[0], 1); imageBookmark(keys[0], 2)

        assertNotNull(rig.reader.removeImageConfirm(listOf(1, 2), keys[0], "Sunset", "Favorites and Trip", dropsPhotoRow = false, dropsBookmark = true))
    }

    @Test fun `a saved photo row of the same collection keeps the copy when only the bookmark goes`() = runBlocking {
        saved(keys[0]); imageBookmark(keys[0], 1); photoRow(keys[0], 1)

        assertNull(rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = false, dropsBookmark = true))
        assertNotNull(rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = true, dropsBookmark = true))
    }

    @Test fun `a kept gallery that lists the photo keeps the copy`() = runBlocking {
        saved(keys[0]); imageBookmark(keys[0], 1); keepGallery(2, listOf(keys[0]))

        assertNull(rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = true, dropsBookmark = true))
    }

    @Test fun `a photo not saved to this phone needs no confirmation`() = runBlocking {
        row(keys[0], OfflineStore.PENDING); imageBookmark(keys[0], 1)

        assertNull(rig.reader.removeImageConfirm(listOf(1), keys[0], "Sunset", "Favorites", dropsPhotoRow = true, dropsBookmark = true))
    }

    @Test fun `turning off Keep offline counts only the copies nothing else uses`() = runBlocking {
        saved(keys[0], bytes = 1_000)                                  // only this gallery
        saved(keys[1], bytes = 3_000); photoRow(keys[1], 2)           // also a saved photo of Trip
        saved(keys[2], bytes = 500); keepGallery(2, listOf(keys[2]))  // also kept by Trip
        keepGallery(1, listOf(keys[0], keys[1], keys[2]))

        val confirm = rig.reader.stopKeepingConfirm(1, gallery, "Class photos")!!

        assertEquals(1, confirm.photos)
        assertEquals(1_000L, confirm.bytes)
        assertEquals(OfflineMessages.stopKeepingConfirm("Class photos", 1, 1_000), confirm.text)
    }

    @Test fun `turning off Keep offline for a gallery with nothing saved needs no confirmation`() = runBlocking {
        row(keys[0], OfflineStore.PENDING); keepGallery(1, listOf(keys[0]))

        assertNull(rig.reader.stopKeepingConfirm(1, gallery, "Class photos"))
    }

    @Test fun `the confirm texts name the cause and the way out`() {
        assertEquals(
            "Remove “Sunset” from Favorites? The copy saved on this phone (2 KB) will be deleted. It stays on SmugMug.",
            OfflineMessages.removeImageConfirm("Sunset", "Favorites", 2_048)
        )
        assertEquals(
            "Stop keeping “Class photos” offline? 1 photo saved on this phone (1 KB) will be deleted. It stays on SmugMug.",
            OfflineMessages.stopKeepingConfirm("Class photos", 1, 1_000)
        )
        assertEquals(
            "Stop keeping “Class photos” offline? 3 photos saved on this phone (1 KB) will be deleted. They stay on SmugMug.",
            OfflineMessages.stopKeepingConfirm("Class photos", 3, 1_000)
        )
    }
}
