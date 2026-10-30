package com.smugview.app.ui

import com.smugview.app.download.NoSourceException
import com.smugview.app.share.HttpFailure
import com.smugview.app.share.NotAPictureException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhotoFileLogicTest {
    private val th = "https://photos.smugmug.com/photos/i-AbC/0/Th/i-AbC-Th.jpg"
    private val orig = "https://photos.smugmug.com/photos/i-AbC/0/D/i-AbC-D.jpg"
    private val x5 = "https://photos.smugmug.com/photos/i-AbC/0/X5/i-AbC-X5.jpg"
    private val x4 = "https://photos.smugmug.com/photos/i-AbC/0/X4/i-AbC-X4.jpg"

    private val plainJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x4A, 0x46,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()
    )

    /** A JPEG with a GPS-ish APP1 (Exif) and a COM segment, which the strip must drop. */
    private val dirtyJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0A, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0, 9, 9,
        0xFF.toByte(), 0xFE.toByte(), 0x00, 0x04, 0x68, 0x69,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()
    )

    private class Net(val answers: Map<String, FetchedImage>) {
        val asked = mutableListOf<String>()
        suspend fun fetch(url: String): FetchedImage {
            asked += url
            return answers[url] ?: FetchedImage(404, null)
        }
    }

    private fun logic(net: Net, files: Map<String, ByteArray> = emptyMap()) = PhotoFileLogic(net::fetch) { files[it] }

    @Test fun shareStripsCameraDataFromTheRendition() = runTest {
        val net = Net(mapOf(th.replace("/Th/", "/X3/") to FetchedImage(200, dirtyJpeg)))
        val out = logic(net).shareBytes(null, th)
        assertTrue(out.size < dirtyJpeg.size)
        assertEquals(0xDA.toByte(), out[3]) // APP1 (Exif) and COM are gone; the scan follows the SOI
        assertEquals(listOf(th.replace("/Th/", "/X3/")), net.asked)
    }

    @Test fun shareUsesTheSavedJpegWithoutTheNetwork() = runTest {
        val net = Net(emptyMap())
        val out = logic(net, mapOf("/p/a.jpg" to dirtyJpeg)).shareBytes("/p/a.jpg", th)
        assertTrue(out.size < dirtyJpeg.size)
        assertTrue(net.asked.isEmpty())
    }

    @Test fun shareIgnoresASavedFileThatIsNotAJpeg() = runTest {
        val net = Net(mapOf(th.replace("/Th/", "/X3/") to FetchedImage(200, plainJpeg)))
        logic(net, mapOf("/p/a.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2))).shareBytes("/p/a.png", th)
        assertEquals(1, net.asked.size)
    }

    @Test fun shareRejectsAnythingThatIsNotAJpeg() = runTest {
        val net = Net(mapOf(th.replace("/Th/", "/X3/") to FetchedImage(200, "<html>".encodeToByteArray())))
        assertFailsWith<NotAPictureException> { logic(net).shareBytes(null, th) }
        assertFailsWith<NotAPictureException> { logic(net).shareBytes(null, null) }
        assertFailsWith<NotAPictureException> { logic(net).shareBytes(null, "http://insecure/Th/x.jpg") }
    }

    @Test fun shareSurfacesBusyAsHttpFailure() = runTest {
        for (code in listOf(429, 503, 404)) {
            val net = Net(mapOf(th.replace("/Th/", "/X3/") to FetchedImage(code, null)))
            assertEquals(code, assertFailsWith<HttpFailure> { logic(net).shareBytes(null, th) }.code)
        }
    }

    @Test fun saveUsesTheSavedCopyFirst() = runTest {
        val net = Net(emptyMap())
        val s = logic(net, mapOf("/p/o.jpg" to plainJpeg)).saveSource("/p/o.jpg", orig, listOf(x5))
        assertTrue(s.original)
        assertContentEquals(plainJpeg, s.bytes)
        assertTrue(net.asked.isEmpty())
    }

    @Test fun saveFallsBackOnlyWhenTheOriginalAnswers404() = runTest {
        val net = Net(mapOf(x4 to FetchedImage(200, plainJpeg)))
        val s = logic(net).saveSource(null, orig, listOf(x5, x4))
        assertFalse(s.original)
        assertEquals(listOf(orig, x5, x4), net.asked)
    }

    @Test fun saveOfTheOriginalIsFlaggedOriginal() = runTest {
        val s = logic(Net(mapOf(orig to FetchedImage(200, plainJpeg)))).saveSource(null, orig, listOf(x5))
        assertTrue(s.original)
    }

    @Test fun saveDoesNotFallBackOnBusyOrServerErrors() = runTest {
        for (code in listOf(429, 500, 503, 403)) {
            val net = Net(mapOf(orig to FetchedImage(code, null), x5 to FetchedImage(200, plainJpeg)))
            assertEquals(code, assertFailsWith<HttpFailure> { logic(net).saveSource(null, orig, listOf(x5)) }.code)
            assertEquals(listOf(orig), net.asked)
        }
    }

    @Test fun saveWhenEverythingIs404IsHttp404() = runTest {
        val code = assertFailsWith<HttpFailure> { logic(Net(emptyMap())).saveSource(null, orig, listOf(x5, x4)) }.code
        assertEquals(404, code)
    }

    @Test fun saveWithNoAddressIsNoSource() = runTest {
        assertFailsWith<NoSourceException> { logic(Net(emptyMap())).saveSource(null, null, listOf(x5)) }
        assertFailsWith<NoSourceException> { logic(Net(emptyMap())).saveSource(null, "http://x/y.jpg", emptyList()) }
    }

    @Test fun namesAndExtensions() {
        assertEquals("shared_i-AbC_1.jpg", PhotoFileLogic.shareFileName("i-AbC_1"))
        assertEquals("shared_iAbC.jpg", PhotoFileLogic.shareFileName("i/A:b*C"))
        assertEquals("IMG_0001", PhotoFileLogic.photoName("k", "IMG_0001.JPG"))
        assertEquals("a_b", PhotoFileLogic.photoName("k", "a/b.png"))
        assertEquals("k", PhotoFileLogic.photoName("k", "  "))
        assertEquals("png", PhotoFileLogic.extensionOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals("gif", PhotoFileLogic.extensionOf(byteArrayOf(0x47, 0x49, 0x46, 0x38)))
        assertEquals("jpg", PhotoFileLogic.extensionOf(plainJpeg))
    }

    @Test fun sweepAgeIsOneDay() {
        val now = 10L * 24 * 3600 * 1000
        assertTrue(PhotoFileLogic.isStale(now - 25L * 3600 * 1000, now))
        assertFalse(PhotoFileLogic.isStale(now - 23L * 3600 * 1000, now))
    }

    @Test fun orientationIsReadFromExif() {
        val exif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8,
            0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 0xFF.toByte(), 0xD9.toByte()
        )
        assertEquals(6, ExifOrientation.read(exif))
        assertEquals(1, ExifOrientation.read(plainJpeg))
        assertEquals(1, ExifOrientation.read(byteArrayOf(1, 2)))
    }
}
