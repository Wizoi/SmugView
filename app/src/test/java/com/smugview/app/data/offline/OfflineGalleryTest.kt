package com.smugview.app.data.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.smugview.app.data.api.RetryingCallFactory
import com.smugview.app.data.api.forFileDownloads
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CachedAlbum
import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.OfflineGallery
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeCdn
import com.smugview.app.data.repository.FakeOriginals
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.LoopbackSmugMug
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Phase 5 step 5-7 (design 2.4 step 3, 6, 8.1 Q3): a gallery kept offline is LISTED by the pass (every page, by
 * `start`, through `Pager`) and its photos are downloaded under the same rules as any other file. Real CDN, API
 * (production client over loopback), Room and WorkManager's test implementation.
 *
 * The data mirrors the owner's: gallery `FfHCms` (NodeID `LCdk7F`, AlbumKey != NodeID) holds 150 photos and the
 * live API pages it at 100, and it sits under the password folder Family `2sDN5x` -> School `P4BKB` -> gallery.
 * Dates are relative to now (findings #9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineGalleryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSmugMugServer()
    private val cacheDir: File = Files.createTempDirectory("smugview-og-cache").toFile()
    private val base: File = Files.createTempDirectory("smugview-og").toFile()
    private val filesDir = File(base, "files").also { it.mkdirs() }
    private lateinit var loopback: LoopbackSmugMug
    private lateinit var cdn: FakeCdn
    private lateinit var db: AppDatabase
    private lateinit var store: OfflineStore
    private lateinit var downloader: OfflineDownloader
    private lateinit var collections: OfflineCollections
    private lateinit var scheduler: OfflineScheduler
    private val passwords = FakePasswordStore()
    private var now = 1_800_000_000_000L

    private val trip = 2L
    private val favorites = 1L
    private val album = OfflineFixture.GALLERY_ALBUM_KEY
    private val keys get() = fake.imageKeysOf(album)

    @Before fun setUp() {
        loopback = LoopbackSmugMug(fake, cacheDir)
        cdn = FakeCdn()
        db = TestDb.inMemory()
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '${OfflineFixture.SITE}', $now)")
            execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '${OfflineFixture.SITE}', $now)")
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        val repository = SmugMugRepository(loopback.api(), db.collectionDao(), passwords, app)
        store = OfflineStore(db, filesDir, runId = "run0001", clock = { now }, freeBytes = { 50L shl 30 })
        val images = RetryingCallFactory(cdn.clientOver(loopback.client).forFileDownloads(), maxAttempts = 0)
        downloader = OfflineDownloader(store, repository, images, apiKey = { "test-key" }, clock = { now })
        scheduler = OfflineScheduler(workManager = { WorkManager.getInstance(app) }, store = store, clock = { now })
        collections = OfflineCollections(db, store, scheduler)
    }

    @After fun tearDown() {
        fake.releaseAllGates()
        cdn.close()
        loopback.close()
        db.close()
        cacheDir.deleteRecursively()
        base.deleteRecursively()
    }

    private val sql get() = db.openHelper.writableDatabase
    private fun cdnPath(key: String) = "/photos/i-$key/"
    private fun count(table: String, where: String = "1=1") =
        sql.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }

    private fun bookmarkGallery() = runBlocking {
        collections.addBookmark(
            CollectionBookmark(collectionId = trip, type = "Album", itemKey = album, title = "New School Year", albumKey = album),
            OfflineFixture.SITE, null
        )
    }

    private fun keep() = runBlocking { collections.keepGalleryOffline(trip, album, OfflineFixture.SITE, "New School Year") }
    private fun gallery(): OfflineGallery? = runBlocking { store.gallery(trip, album) }
    private fun items(): List<String> = runBlocking { db.offlineDao().itemsOf(trip, album).map { it.imageKey } }
    private fun passWifi() = runBlocking { downloader.runPass(NetworkClass.UNMETERED) }
    private fun passAny() = runBlocking { downloader.runPass(NetworkClass.ANY) }
    private fun row(key: String): OfflineFile? = runBlocking { store.file(OfflineStore.fileKeyOf(key)) }
    private fun filesInOffline() = File(filesDir, "offline").listFiles()?.filter { it.isFile }.orEmpty()
    private fun imageListRequests() = fake.requests.count { it.contains("$album!images") }

    private fun cacheAlbum(ilu: String?) = runBlocking {
        db.collectionDao().upsertAlbums(
            listOf(
                CachedAlbum(
                    albumKey = album, nodeId = OfflineFixture.GALLERY_NODE_ID, name = "New School Year", securityType = "None",
                    passwordHint = null, uri = "/api/v2/album/$album", webUri = null,
                    urlPath = "/Family/School/2026-09-01--New-School-Year", imageCount = 150, dateModified = fake.daysAgo(6),
                    galleryStyle = null, highlightImageUrl = null, sortIndex = 0, nickname = OfflineFixture.SITE,
                    parentNodeId = "P4BKB", imagesLastUpdated = ilu
                )
            )
        )
    }

    /** Family (password) -> School -> gallery, so `rootOf` resolves from the cache with no request. */
    private fun cacheFamilyTree() = runBlocking {
        fun row(id: String, parent: String?, type: String, title: String, albumUri: String? = null) = CachedNode(
            nodeId = id, parentNodeId = parent, type = type, title = title, description = null,
            access = if (id == "2sDN5x") "Password" else "None", passwordHint = null, uri = "/api/v2/node/$id",
            childNodesUri = null, albumUri = albumUri, nickname = OfflineFixture.SITE
        )
        db.collectionDao().insertNodes(
            listOf(
                row("2sDN5x", "root", "Folder", "Family"),
                row("P4BKB", "2sDN5x", "Folder", "School"),
                row(OfflineFixture.GALLERY_NODE_ID, "P4BKB", "Album", "New School Year", "/api/v2/album/$album")
            )
        )
    }

    private fun photo(key: String, collection: Long) = CollectionPhoto(
        imageKey = key, collectionId = collection, albumKey = album, title = "IMG_$key",
        thumbnailUrl = "https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg",
        archivedUri = FakeOriginals.archivedUri(key), localFilePath = null,
        dateTaken = Instant.now().minus(Duration.ofDays(10)).toString(), keywords = null
    )

    /** The gallery listed its photos (under the gallery's current rule) but the pass has not downloaded them yet. */
    private fun listedButNotDownloaded() = runBlocking {
        val g = store.gallery(trip, album)!!
        store.applyListing(
            g, keys.map { OfflineStore.ListedImage(it, FakeOriginals.archivedUri(it), FakeOriginals.size(it), FakeOriginals.md5(it), null, null, "JPG", null) },
            null
        )
    }

    private fun assertAllDone(expected: List<String>) {
        for (key in expected) {
            val r = row(key)
            assertNotNull("row for $key", r)
            assertEquals("$key should be DONE", "DONE", r!!.state)
            assertEquals(FakeOriginals.size(key), File(filesDir, r.relPath!!).length())
        }
    }

    // ---- listing and downloading ----

    @Test fun aKeptGalleryIsListedPageByPageAndEveryPhotoIsDownloaded() {
        bookmarkGallery()
        keep()
        assertEquals("kept, not listed yet", "LIST_PENDING", gallery()!!.state)

        val result = passWifi()

        val g = gallery()!!
        assertEquals("LISTED", g.state)
        assertEquals(150, g.photoCount)
        assertEquals("the items are the listing, in order", keys, items())
        assertEquals("two pages of the listing (100 + 50)", 2, imageListRequests())
        assertEquals(150, count("offline_files"))
        assertEquals("each row carries the listed size and MD5", 150,
            count("offline_files", "expectedBytes IS NOT NULL AND md5 IS NOT NULL AND sourceUrl IS NOT NULL"))
        assertEquals(FakeOriginals.size(keys[120]), row(keys[120])!!.expectedBytes)
        assertEquals(FakeOriginals.md5(keys[120]), row(keys[120])!!.md5)
        assertAllDone(keys)
        assertEquals(150, result.downloaded)
        assertEquals("one request per photo", 150, keys.sumOf { cdn.countFor(cdnPath(it)) })
    }

    @Test fun aWifiOnlyGalleryIsNotListedOrDownloadedOnMobileDataUntilTheUserAllowsIt() {
        bookmarkGallery()
        keep()
        assertTrue("the default is Wi-Fi only (Q3)", gallery()!!.wifiOnly)

        passAny()

        assertEquals("not listed on a metered network", "LIST_PENDING", gallery()!!.state)
        assertEquals(0, imageListRequests())
        assertEquals(0, cdn.requests.size)
        assertTrue("a Wi-Fi-only gallery is a reason for the unmetered work", runBlocking { store.countWantedWifiOnly() } > 0)

        runBlocking { collections.setGalleryWifiOnly(trip, album, false) }
        assertFalse(gallery()!!.wifiOnly)
        passAny()

        assertEquals("LISTED", gallery()!!.state)
        assertAllDone(keys)
        assertEquals("allowed on mobile data: no Wi-Fi pass was needed", 0, count("offline_files", "wifiOnly = 1"))
    }

    @Test fun takingTheMobileDataChoiceBackMakesTheUnfinishedFilesWaitForWifiAgain() {
        bookmarkGallery()
        keep()
        runBlocking { collections.setGalleryWifiOnly(trip, album, false) }
        listedButNotDownloaded()
        assertEquals(150, count("offline_files", "wifiOnly = 0 AND state = 'PENDING'"))

        runBlocking { collections.setGalleryWifiOnly(trip, album, true) }

        assertEquals(150, count("offline_files", "wifiOnly = 1 AND state = 'PENDING'"))
        passAny()
        assertEquals("nothing downloads on mobile data now", 0, cdn.requests.size)
    }

    @Test fun keepingAGalleryAgainDoesNotResetAListedOne() {
        bookmarkGallery()
        keep()
        passWifi()
        val requests = imageListRequests()

        keep()

        assertEquals("LISTED", gallery()!!.state)
        assertEquals(150, items().size)
        assertEquals(requests, imageListRequests())
    }

    @Test fun aPhotoSavedAloneIsNotMadeToWaitForWifiByAGalleryThatListedItFirst() {
        bookmarkGallery()
        keep()
        val key = keys[5]
        // The gallery listed its photos (Wi-Fi only) but the pass has not downloaded them yet.
        runBlocking {
            val g = store.gallery(trip, album)!!
            store.applyListing(
                g, keys.map { OfflineStore.ListedImage(it, FakeOriginals.archivedUri(it), FakeOriginals.size(it), FakeOriginals.md5(it), null, null, "JPG", null) },
                null
            )
        }
        assertTrue(row(key)!!.wifiOnly)

        runBlocking { collections.savePhoto(photo(key, favorites), OfflineFixture.SITE) }
        passAny()

        assertFalse("saved alone: any network", row(key)!!.wifiOnly)
        assertEquals("DONE", row(key)!!.state)
        assertEquals("only that photo is taken on mobile data", 1, count("offline_files", "state = 'DONE'"))
        assertEquals(1, cdn.requests.size)
    }

    // ---- unbookmarking ----

    @Test fun unbookmarkingWhileOfflineRemovesTheGalleryAndItsFilesWithNoNetworkRequest() {
        bookmarkGallery()
        keep()
        passWifi()
        assertEquals(150, filesInOffline().size)
        loopback.online = false
        val apiBefore = fake.requests.size
        val cdnBefore = cdn.requests.size

        runBlocking { collections.removeBookmark(trip, "Album", album) }

        assertNull(gallery())
        assertEquals(0, items().size)
        assertEquals(0, count("offline_files"))
        assertEquals("the files are deleted at once", 0, filesInOffline().size)
        assertEquals("no request", apiBefore, fake.requests.size)
        assertEquals(cdnBefore, cdn.requests.size)
    }

    @Test fun aGalleryPhotoAlsoSavedAloneSurvivesUnbookmarkingTheGallery() {
        bookmarkGallery()
        keep()
        runBlocking { collections.savePhoto(photo(keys[7], favorites), OfflineFixture.SITE) }
        passWifi()
        assertEquals(150, filesInOffline().size)

        runBlocking { collections.removeBookmark(trip, "Album", album) }

        assertEquals("only the saved photo is kept", listOf(OfflineStore.fileKeyOf(keys[7])), runBlocking { db.offlineDao().allFiles() }.map { it.fileKey })
        assertEquals(1, filesInOffline().size)
        assertAllDone(listOf(keys[7]))
    }

    @Test fun unbookmarkingWhileTheListingIsOnTheWireLeavesNothingBehind() = runBlocking<Unit> {
        bookmarkGallery()
        keep()
        val gate = fake.hold("$album!images")
        val running = async(Dispatchers.IO) { downloader.runPass(NetworkClass.UNMETERED) }
        assertTrue("the listing reached the server", gate.awaitArrived())

        collections.removeBookmark(trip, "Album", album)
        gate.release()
        running.await()

        assertNull(gallery())
        assertEquals(0, count("offline_files"))
        assertEquals(0, count("offline_gallery_items"))
        assertEquals(0, filesInOffline().size)
    }

    // ---- password galleries (R-21, 4-8b) ----

    @Test fun aPasswordGalleryWithASavedPasswordUnlocksOnceThenIsListed() {
        passwords.savePassword("2sDN5x", "test-pw-1")
        fake.gateImages = true
        fake.cookieGate = true
        cacheFamilyTree()
        bookmarkGallery()
        keep()

        passWifi()

        assertEquals("LISTED", gallery()!!.state)
        assertEquals("the saved password was used once", 1, fake.requests.count { it.contains("!unlock") })
        assertEquals(150, items().size)
        assertAllDone(keys)
        assertEquals("the password is still saved", "test-pw-1", passwords.getPassword("2sDN5x"))
    }

    @Test fun aPasswordTheServerCannotTryJustNowIsARetryableLockAndTheFilesStayAbsent() {
        passwords.savePassword("2sDN5x", "test-pw-1")
        fake.gateImages = true
        fake.cookieGate = true
        fake.unlockCode = 503
        cacheFamilyTree()
        bookmarkGallery()
        keep()

        passWifi()

        val g = gallery()!!
        assertEquals("FAILED", g.state)
        assertEquals("LOCKED", g.failure)
        assertTrue("retryable: the password was not tried, not wrong", g.retryable)
        assertEquals(0, count("offline_files"))
        assertEquals(0, cdn.requests.size)
        assertEquals("a password is never deleted (R-21)", "test-pw-1", passwords.getPassword("2sDN5x"))
        assertEquals("a retry is scheduled", g.listedAt!! + OfflineStore.GALLERY_RETRY_GAP_MS, runBlocking { store.earliestRetryAt(true) })

        // Later the server answers: the retry lists it.
        fake.unlockCode = 200
        now += OfflineStore.GALLERY_RETRY_GAP_MS
        passWifi()

        assertEquals("LISTED", gallery()!!.state)
        assertAllDone(keys)
    }

    @Test fun aGalleryWithNoPasswordIsPermanentlyLockedUntilASessionBumpRequeuesIt() {
        fake.gateImages = true
        fake.cookieGate = true
        cacheFamilyTree()
        bookmarkGallery()
        keep()

        passWifi()

        val g = gallery()!!
        assertEquals("FAILED", g.state)
        assertEquals("LOCKED", g.failure)
        assertFalse("nothing to retry until the user unlocks", g.retryable)
        assertEquals("a permanent lock does not keep waking the scheduler", 0, runBlocking { store.countWanted() })

        // The user opens the gallery and types the password: a root came into session.
        passwords.savePassword("2sDN5x", "test-pw-1")
        assertEquals(1, runBlocking { store.requeueLockedGalleries() })
        assertEquals("LIST_PENDING", gallery()!!.state)
        passWifi()

        assertEquals("LISTED", gallery()!!.state)
        assertAllDone(keys)
    }

    // ---- E1 (5-11, live): `image/{key}-0` in a password gallery is a 404 until the session exists ----

    private fun wantImageWithNoSource(key: String) = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                "VALUES ($trip, 'Image', '$key', 'IMG_$key', '$album', 'New School Year', NULL)"
        )
        store.request(key, albumKey = album, sourceUrl = null, expectedBytes = null, md5 = null, wifiOnly = false)
    }

    @Test fun aPasswordGalleryPhotoWithNoSourceUrlIsResolvedAfterTheSavedPasswordUnlocksIt() {
        passwords.savePassword("2sDN5x", "test-pw-1")
        fake.gateImages = true
        fake.cookieGate = true
        cacheFamilyTree()
        wantImageWithNoSource(keys[0])

        passAny()

        assertAllDone(listOf(keys[0]))
        assertEquals("the saved password was used once", 1, fake.requests.count { it.contains("!unlock") })
        assertEquals("the password is still saved", "test-pw-1", passwords.getPassword("2sDN5x"))
    }

    // ---- failing half-way ----

    @Test fun offlineBeforeTheListingLeavesTheGalleryRetryableAndNothingDone() {
        bookmarkGallery()
        keep()
        loopback.online = false

        passWifi()

        val g = gallery()!!
        assertEquals("FAILED", g.state)
        assertEquals("OFFLINE", g.failure)
        assertTrue(g.retryable)
        assertEquals(0, count("offline_files"))
        assertEquals(0, count("offline_files", "state = 'DONE'"))
        assertTrue("still wanted", runBlocking { store.countWanted() } > 0)

        loopback.online = true
        now += OfflineStore.GALLERY_RETRY_GAP_MS
        passWifi()

        assertEquals("LISTED", gallery()!!.state)
        assertAllDone(keys)
    }

    @Test fun aRateLimitOnTheSecondPageLeavesNoHalfListing() {
        bookmarkGallery()
        keep()
        fake.respond429("start=101", 1)

        passWifi()

        val g = gallery()!!
        assertEquals("FAILED", g.state)
        assertEquals("BUSY", g.failure)
        assertTrue(g.retryable)
        assertEquals("all of the listing or none of it", 0, items().size)
        assertEquals(0, count("offline_files"))
        assertEquals(0, cdn.requests.size)

        now += OfflineStore.GALLERY_RETRY_GAP_MS
        passWifi()

        assertEquals("LISTED", gallery()!!.state)
        assertEquals(150, items().size)
        assertAllDone(keys)
    }

    @Test fun aFailedRelistingKeepsTheEarlierListingAndItsFiles() {
        bookmarkGallery()
        keep()
        cacheAlbum(fake.daysAgo(5))
        passWifi()
        assertAllDone(keys)
        cacheAlbum(fake.daysAgo(0))
        fake.respond429("$album!images", 1)

        passWifi()

        assertEquals("FAILED", gallery()!!.state)
        assertEquals("the earlier items are still there", 150, items().size)
        assertEquals(150, count("offline_files", "state = 'DONE'"))
        assertEquals(150, filesInOffline().size)
    }

    // ---- changes on SmugMug ----

    @Test fun aChangedMd5ForADonePhotoDownloadsItAgainOnce() {
        bookmarkGallery()
        keep()
        cacheAlbum(fake.daysAgo(5))
        passWifi()
        val changed = keys[10]
        // The row remembers the OLD original; the listing now says something else.
        sql.execSQL("UPDATE offline_files SET md5 = '00000000000000000000000000000000' WHERE imageKey = '$changed'")
        cacheAlbum(fake.daysAgo(0))

        passWifi()

        assertEquals("the changed photo was fetched again", 2, cdn.countFor(cdnPath(changed)))
        assertEquals("the others were not", 1, cdn.countFor(cdnPath(keys[11])))
        assertEquals(FakeOriginals.md5(changed), row(changed)!!.md5)
        assertAllDone(keys)
        assertEquals(150, filesInOffline().size)
    }

    @Test fun anImagesLastUpdatedNewerThanTheListedOneRelistsAndDropsARemovedPhoto() {
        bookmarkGallery()
        keep()
        cacheAlbum(fake.daysAgo(5))
        passWifi()
        assertEquals(fake.daysAgo(5).take(10), gallery()!!.listedIlu!!.take(10))
        val removed = keys.last()
        assertEquals(150, items().size)

        // Not changed: a pass lists nothing.
        val before = imageListRequests()
        passWifi()
        assertEquals("ILU unchanged: no relisting", before, imageListRequests())

        fake.imageCounts[album] = 149
        cacheAlbum(fake.daysAgo(0))
        passWifi()

        assertTrue("relisted", imageListRequests() > before)
        assertEquals(149, items().size)
        assertEquals(149, gallery()!!.photoCount)
        assertFalse(removed in items())
        assertNull("the dropped photo's row is gone", row(removed))
        assertEquals("and its file", 149, filesInOffline().size)
        assertEquals("nothing else was fetched again", 1, cdn.countFor(cdnPath(keys[0])))
        assertTrue("the new ILU is recorded", gallery()!!.listedIlu!! > fake.daysAgo(5))
    }

    @Test fun aGalleryThatChangedIsAReasonToSchedule() {
        bookmarkGallery()
        keep()
        cacheAlbum(fake.daysAgo(5))
        passWifi()
        assertEquals("a finished gallery wants nothing", 0, runBlocking { store.countWanted() })

        cacheAlbum(fake.daysAgo(0))

        assertEquals(1, runBlocking { store.countWanted() })
        assertEquals(1, runBlocking { store.countWantedWifiOnly() })
    }

    @Test fun threeGalleriesAtMostAreListedPerPassAndTheRestFollow() {
        for (k in listOf("Ak0001", "Ak0002", "Ak0003", "Ak0004")) {
            runBlocking { collections.keepGalleryOffline(trip, k, OfflineFixture.SITE, k) }
        }
        for (k in listOf("Ak0001", "Ak0002", "Ak0003", "Ak0004")) fake.imageCounts[k] = 2

        val first = passWifi()

        assertEquals(3, count("offline_galleries", "state = 'LISTED'"))
        assertTrue("one is left, so the scheduler appends a run", first.more)

        val second = passWifi()

        assertEquals(4, count("offline_galleries", "state = 'LISTED'"))
        assertFalse(second.more)
    }
}
