package com.smugview.app.share

import androidx.exifinterface.media.ExifInterface
import com.smugview.app.testutil.JpegFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 6-14: the file a share hands to another app (design 3.10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShareFilesTest {
    private lateinit var cache: File
    private val requested = ArrayList<String>()
    private var code = 200
    private var body: ByteArray = ByteArray(0)
    private val thumb = "https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/Th/i-AbC123-Th.jpg"

    private val images = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requested += chain.request().url.toString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("image/jpeg".toMediaType())).build()
    }).build()

    @Before fun setUp() { cache = java.nio.file.Files.createTempDirectory("share-files").toFile() }
    @After fun tearDown() { cache.deleteRecursively() }

    private fun private_(): ByteArray = JpegFixture.withPrivateData(cache)

    private fun exifOf(f: File) = ExifInterface(f.absolutePath)

    @Test fun `a saved JPEG is shared stripped, and nothing is fetched`() = runBlocking {
        val saved = File(cache, "saved.jpg").also { it.writeBytes(private_()) }
        val out = ShareFiles(cache, images).prepare("AbC123", saved, thumb)
        assertTrue(requested.isEmpty())
        assertNull(exifOf(out).getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertNull(exifOf(out).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exifOf(out).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("shared_images", out.parentFile!!.name)
        assertTrue("the saved original is untouched", ExifInterface(saved.absolutePath).getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER) == JpegFixture.SERIAL)
    }

    @Test fun `with nothing saved the X3 rendition is fetched and stripped`() = runBlocking {
        body = private_()
        val out = ShareFiles(cache, images).prepare("AbC123", null, thumb)
        assertEquals(listOf("https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/X3/i-AbC123-Th.jpg"), requested)
        assertNull(exifOf(out).getAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME))
    }

    @Test fun `a saved file that is not a JPEG falls back to the X3 rendition`() = runBlocking {
        val saved = File(cache, "saved.png").also { it.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)) }
        body = private_()
        ShareFiles(cache, images).prepare("AbC123", saved, thumb)
        assertEquals(1, requested.size)
    }

    @Test fun `a failed fetch throws and leaves no half file behind`() {
        code = 503
        try {
            runBlocking { ShareFiles(cache, images).prepare("AbC123", null, thumb) }
            fail("expected an HttpFailure")
        } catch (e: ShareFiles.HttpFailure) {
            assertEquals(503, e.code)
            assertEquals(emptyList<String>(), File(cache, "shared_images").listFiles()!!.map { it.name })
        }
    }

    @Test fun `bytes that are not a JPEG are not shared`() {
        body = "<html>not a picture</html>".toByteArray()
        try {
            runBlocking { ShareFiles(cache, images).prepare("AbC123", null, thumb) }
            fail("expected NotAPictureException")
        } catch (e: ShareFiles.NotAPictureException) {
            assertEquals(emptyList<String>(), File(cache, "shared_images").listFiles()!!.map { it.name })
        }
    }

    @Test fun `files older than a day are swept at the next share, newer ones stay`() = runBlocking {
        val dir = File(cache, "shared_images").apply { mkdirs() }
        val now = 10L * 24 * 3600 * 1000
        val old = File(dir, "shared_old.jpg").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - 25L * 3600 * 1000) }
        val fresh = File(dir, "shared_fresh.jpg").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - 3L * 3600 * 1000) }
        val saved = File(cache, "saved.jpg").also { it.writeBytes(JpegFixture.plain()) }
        ShareFiles(cache, images, clock = { now }).prepare("AbC123", saved, thumb)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun `an image key cannot climb out of the folder`() = runBlocking {
        val saved = File(cache, "saved.jpg").also { it.writeBytes(JpegFixture.plain()) }
        val out = ShareFiles(cache, images).prepare("../../evil", saved, thumb)
        assertEquals(File(cache, "shared_images").canonicalPath, out.parentFile!!.canonicalPath)
    }
}
