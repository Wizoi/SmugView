package com.smugview.app.download

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.smugview.app.share.ShareFiles
import com.smugview.app.ui.text.UserMessages
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stand-in for the system photo store: it records every row and keeps each picture's bytes in a file. */
class FakePhotoStore : ContentProvider() {
    class Row(val id: Long, val values: ContentValues, val file: File)

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val id = nextId++
        val file = File(dir, "row$id")
        rows += Row(id, ContentValues(values), file)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int {
        val row = rows.firstOrNull { it.id == ContentUris.parseId(uri) } ?: return 0
        row.values.putAll(values!!)
        return 1
    }

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
        val row = rows.firstOrNull { it.id == ContentUris.parseId(uri) } ?: return 0
        rows.remove(row)
        row.file.delete()
        return 1
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = rows.first { it.id == ContentUris.parseId(uri) }
        return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    companion object {
        lateinit var dir: File
        val rows = ArrayList<Row>()
        private var nextId = 1L
        fun reset(d: File) { dir = d; rows.clear(); nextId = 1L }
    }
}

/** Step 6-15 (design 3.11, R-55): the original goes to Pictures/SmugView, whole or not at all, and never over another file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PhotoDownloaderTest {
    private lateinit var work: File
    private lateinit var context: Context
    private val requested = ArrayList<String>()
    private var code = 200
    private var body = ByteArray(0)
    private var contentType = "image/jpeg"
    private var claimedLength = -1L
    private var offline = false
    private val codeFor = HashMap<String, Int>()
    private val bodyFor = HashMap<String, ByteArray>()
    private val cdn = "https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/D/i-AbC123-D.jpg"

    private val images = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val asked = chain.request().url.toString()
        requested += asked
        if (offline) throw IOException("Unable to resolve host")
        val bytes = bodyFor[asked] ?: body
        val length = if (claimedLength >= 0) claimedLength else bytes.size.toLong()
        val type = contentType.toMediaType()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(codeFor[asked] ?: code).message("x")
            .body(object : ResponseBody() {
                override fun contentType(): MediaType = type
                override fun contentLength() = length
                override fun source(): BufferedSource = Buffer().write(bytes)
            }).build()
    }).build()

    @Before fun setUp() {
        work = java.nio.file.Files.createTempDirectory("downloader").toFile()
        FakePhotoStore.reset(File(work, "store").apply { mkdirs() })
        Robolectric.buildContentProvider(FakePhotoStore::class.java).create(MediaStore.AUTHORITY)
        context = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun downloader(sdk: Int = 33, permission: Boolean = true, scanned: MutableList<String> = ArrayList()) =
        PhotoDownloader(context, images, sdkInt = sdk, legacyPicturesDir = { File(work, "Pictures") },
            hasLegacyPermission = { permission }, scan = { path, _ -> scanned += path })

    private val bytes = ByteArray(2048) { (it * 7).toByte() }

    /** Red on the old code: it wrote `Downloads/{name}.jpg` with no photo-store row at all. */
    @Test fun `on Android 13 one row lands in Pictures slash SmugView, published, with the original bytes`() = runBlocking {
        body = bytes
        val name = downloader().download("AbC123", "Beach Day.jpg", null, cdn).name

        assertEquals("Beach Day.jpg", name)
        val row = FakePhotoStore.rows.single()
        assertEquals("Pictures/SmugView", row.values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("Beach Day.jpg", row.values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("published, not pending", 0, row.values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertArrayEquals(bytes, row.file.readBytes())
    }

    /** Red on the old code: every download was named `.jpg`, whatever it was. */
    @Test fun `a PNG original is saved as a png`() = runBlocking {
        body = bytes; contentType = "image/png"
        val name = downloader().download("AbC123", "Scan 4.jpg", null, cdn).name

        assertEquals("Scan 4.png", name)
        assertEquals("image/png", FakePhotoStore.rows.single().values.getAsString(MediaStore.Images.Media.MIME_TYPE))
    }

    /** Red on the old code: a connection that dropped left a truncated file under Downloads. */
    @Test fun `a body that ends early leaves no row and no file`() {
        body = bytes.copyOf(500); claimedLength = 2048
        try {
            runBlocking { downloader().download("AbC123", "Beach.jpg", null, cdn) }
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("ended early"))
        }
        assertTrue("no row", FakePhotoStore.rows.isEmpty())
        assertTrue("no file", FakePhotoStore.dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `the same photo twice is two rows, the second never replaces the first`() = runBlocking {
        body = bytes
        downloader().download("AbC123", "Beach.jpg", null, cdn)
        downloader().download("AbC123", "Beach.jpg", null, cdn)

        assertEquals(2, FakePhotoStore.rows.size)
        assertTrue(FakePhotoStore.rows.all { it.file.length() == bytes.size.toLong() })
    }

    @Test fun `a saved copy is used, and offline nothing is requested`() = runBlocking {
        val saved = File(work, "saved.orig.jpg").also { it.writeBytes(bytes) }
        offline = true
        val name = downloader().download("AbC123", "Beach.jpg", saved, cdn).name

        assertTrue("no CDN request: $requested", requested.isEmpty())
        assertEquals("Beach.jpg", name)
        assertArrayEquals(bytes, FakePhotoStore.rows.single().file.readBytes())
    }

    @Test fun `offline with nothing saved says so, and a 503 does not`() {
        offline = true
        val offlineError = try { runBlocking { downloader().download("AbC123", "Beach.jpg", null, cdn) }; null } catch (e: Exception) { e }
        assertEquals(UserMessages.DOWNLOAD_OFFLINE, downloadMessage(offlineError!!))

        offline = false; code = 503
        val serverError = try { runBlocking { downloader().download("AbC123", "Beach.jpg", null, cdn) }; null } catch (e: Exception) { e }
        assertTrue(serverError is ShareFiles.HttpFailure)
        assertFalse(downloadMessage(serverError!!).contains("offline", ignoreCase = true))
        assertTrue(downloadMessage(serverError), downloadMessage(serverError).startsWith("Couldn't save the photo."))
        assertTrue("no row left", FakePhotoStore.rows.isEmpty())
    }

    @Test fun `a full phone says so`() {
        assertEquals(UserMessages.DOWNLOAD_NO_SPACE, downloadMessage(IOException("write failed: ENOSPC (No space left on device)")))
    }

    /** Red on the old code: it never asked, and the write failed silently on Android 8 and 9. */
    @Test fun `on Android 9 without the permission it stops and says what to do`() {
        body = bytes
        val e = try { runBlocking { downloader(sdk = 28, permission = false).download("AbC123", "Beach.jpg", null, cdn) }; null } catch (e: Exception) { e }

        assertTrue(e is PhotoDownloader.PermissionNeededException)
        assertEquals(UserMessages.DOWNLOAD_PERMISSION, downloadMessage(e!!))
        assertTrue("nothing requested", requested.isEmpty())
    }

    @Test fun `on Android 9 with the permission the file is written whole, never over another, and scanned`() = runBlocking {
        body = bytes
        val scanned = ArrayList<String>()
        val d = downloader(sdk = 28, scanned = scanned)
        val first = d.download("AbC123", "Beach.jpg", null, cdn).name
        val second = d.download("AbC123", "Beach.jpg", null, cdn).name

        val dir = File(work, "Pictures/SmugView")
        assertEquals("Beach.jpg", first)
        assertEquals("Beach (1).jpg", second)
        assertEquals(listOf("Beach (1).jpg", "Beach.jpg"), dir.list()!!.sorted())
        assertArrayEquals(bytes, File(dir, "Beach.jpg").readBytes())
        assertEquals(2, scanned.size)
    }

    @Test fun `a cut download on Android 9 leaves no file and no part file`() {
        body = bytes.copyOf(500); claimedLength = 2048
        try { runBlocking { downloader(sdk = 28).download("AbC123", "Beach.jpg", null, cdn) } } catch (_: IOException) { }

        assertTrue(File(work, "Pictures/SmugView").list().orEmpty().isEmpty())
    }

    @Test fun `the name is the API FileName without its extension, safe for a file, else the key`() {
        assertEquals("a_b_c", PhotoDownloader.nameFor("K1", "a/b:c.jpg"))
        assertEquals("K1", PhotoDownloader.nameFor("K1", null))
        assertEquals("K1", PhotoDownloader.nameFor("K1", "   "))
        assertEquals("K1", PhotoDownloader.nameFor("K1", ".jpg"))
    }

    @Test fun `nothing saved and no address is its own failure`() {
        val e = try { runBlocking { downloader().download("AbC123", "Beach.jpg", null, null) }; null } catch (e: Exception) { e }
        assertTrue(e is PhotoDownloader.NoSourceException)
    }

    private val original = cdn
    private val x5 = "https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/X5/i-AbC123-X5.jpg"
    private val x3 = "https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/X3/i-AbC123-X3.jpg"
    private val smaller = ByteArray(900) { (it * 3).toByte() }

    /** Red on the old code: an original that answers 404 (107 of 850 in one gallery, findings #24) failed the save, with "Error 404.", while the photo was on screen. */
    @Test fun `an original that answers 404 saves the largest size SmugMug has, and says it is not the original`() = runBlocking {
        codeFor[original] = 404; bodyFor[x5] = smaller
        val saved = downloader().download("AbC123", "Beach.jpg", null, original, Renditions.fromOriginal(original))

        assertEquals(listOf(original, x5), requested)
        assertFalse("not the original", saved.original)
        assertEquals("Beach.jpg", saved.name)
        assertArrayEquals(smaller, FakePhotoStore.rows.single().file.readBytes())
    }

    @Test fun `a size that is missing too is skipped for the next one`() = runBlocking {
        codeFor[original] = 404; codeFor[x5] = 404; bodyFor[x3] = smaller
        val saved = downloader().download("AbC123", "Beach.jpg", null, original, listOf(x5, x3))

        assertEquals(listOf(original, x5, x3), requested)
        assertArrayEquals(smaller, FakePhotoStore.rows.single().file.readBytes())
        assertFalse(saved.original)
    }

    @Test fun `an original that answers is saved as the original and no smaller size is asked for`() = runBlocking {
        body = bytes
        val saved = downloader().download("AbC123", "Beach.jpg", null, original, listOf(x5, x3))

        assertEquals(listOf(original), requested)
        assertTrue(saved.original)
    }

    @Test fun `a 503 on the original does not fall back, because SmugMug is busy and not missing the photo`() {
        code = 503
        val e = try { runBlocking { downloader().download("AbC123", "Beach.jpg", null, original, listOf(x5)) }; null } catch (e: Exception) { e }

        assertEquals(listOf(original), requested)
        assertTrue(downloadMessage(e!!), downloadMessage(e).contains("having trouble"))
    }

    /** Red on the old code: the toast said "Couldn't save the photo. Error 404." with no cause and no way out. */
    @Test fun `when SmugMug has no size of the photo at all, the message says so and not Error 404`() {
        code = 404
        val e = try { runBlocking { downloader().download("AbC123", "Beach.jpg", null, original, listOf(x5, x3)) }; null } catch (e: Exception) { e }

        assertEquals(listOf(original, x5, x3), requested)
        assertEquals(UserMessages.DOWNLOAD_NO_COPY, downloadMessage(e!!))
        assertFalse(UserMessages.DOWNLOAD_NO_COPY.contains("404"))
        assertTrue("no row left", FakePhotoStore.rows.isEmpty())
    }

    @Test fun `the smaller sizes come from the thumbnail or the original, largest first, and from nothing else`() {
        val th = "https://photos.smugmug.com/photos/i-K/0/h/Th/i-K-Th.jpg"
        assertEquals(
            listOf("X5", "X4", "X3", "XL", "L").map { "https://photos.smugmug.com/photos/i-K/0/h/$it/i-K-$it.jpg" },
            Renditions.fromThumbnail(th)
        )
        assertEquals("https://p.example/Family/i-K/0/X5/i-K-X5.jpg", Renditions.fromOriginal("https://p.example/Family/i-K/0/D/i-K-D.jpg").first())
        assertTrue(Renditions.fromThumbnail(null).isEmpty())
        assertTrue(Renditions.fromThumbnail("https://p.example/some/other.jpg").isEmpty())
        assertTrue(Renditions.fromOriginal("http://p.example/D/i-K-D.jpg").isEmpty())
    }
}
