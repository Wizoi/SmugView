package com.smugview.app.share

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.smugview.app.testutil.JpegFixture
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 6-14: a shared picture carries no camera serial, owner, artist, GPS or XMP (R-53), and its pixels are untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class JpegStripTest {
    private fun fixture(): ByteArray {
        val dir = java.nio.file.Files.createTempDirectory("strip").toFile()
        try { return JpegFixture.withPrivateData(dir) } finally { dir.deleteRecursively() }
    }

    private fun exifOf(jpeg: ByteArray): ExifInterface = ExifInterface(java.io.ByteArrayInputStream(jpeg))

    private fun scanData(jpeg: ByteArray): ByteArray {
        var i = 2
        while (true) {
            val type = jpeg[i + 1].toInt() and 0xFF
            if (type == 0xDA) return jpeg.copyOfRange(i, jpeg.size)
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
    }

    private fun segment(type: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), type.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
    }

    private fun withSegmentsAfterSoi(jpeg: ByteArray, vararg segs: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        segs.forEach { out.write(it) }
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    private fun latin1(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun `the fixture really carries what must be stripped`() {
        val src = fixture()
        assertEquals(JpegFixture.SERIAL, exifOf(src).getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertTrue(JpegFixture.hasXmp(src))
    }

    @Test fun `exif tags and xmp are gone, orientation is kept, pixels are byte for byte the same`() {
        val src = fixture()
        val out = JpegStrip.strip(src, orientation = 6)!!
        val exif = exifOf(out)
        assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertNull(exif.getAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME))
        assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertFalse(JpegFixture.hasXmp(out))
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse(text.contains(JpegFixture.SERIAL))
        assertFalse(text.contains(JpegFixture.OWNER))
        assertFalse(text.contains(JpegFixture.ARTIST))
        assertArrayEquals(scanData(src), scanData(out))
        assertNotNull(BitmapFactory.decodeByteArray(out, 0, out.size))
    }

    @Test fun `a normally oriented photo gets no exif block at all`() {
        val out = JpegStrip.strip(fixture(), orientation = 1)!!
        assertTrue(exifOf(out).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) <= 1)
        assertFalse(String(out, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test fun `every orientation survives`() {
        for (o in 2..8) {
            val out = JpegStrip.strip(fixture(), orientation = o)!!
            assertEquals(o, exifOf(out).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        }
    }

    @Test fun `several APP1 segments and an APP1 near the 64 KB limit are all removed`() {
        val big = segment(0xE1, latin1("Exif\u0000\u0000") + ByteArray(65_000) { 'S'.code.toByte() } + JpegFixture.SERIAL.toByteArray())
        val src = withSegmentsAfterSoi(
            JpegFixture.plain(), big, big,
            segment(0xED, latin1("Photoshop 3.0\u0000IPTC")), segment(0xFE, "made by Kevin".toByteArray())
        )
        val out = JpegStrip.strip(src)!!
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse(text.contains(JpegFixture.SERIAL))
        assertFalse(text.contains("Photoshop"))
        assertFalse(text.contains("made by Kevin"))
        assertTrue(out.size < 5_000)
        assertArrayEquals(scanData(src), scanData(out))
    }

    @Test fun `the ICC profile and the Adobe marker stay because the colours depend on them`() {
        val icc = segment(0xE2, latin1("ICC_PROFILE\u0000\u0001\u0001") + ByteArray(40))
        val mpf = segment(0xE2, latin1("MPF\u0000") + ByteArray(20))
        val adobe = segment(0xEE, latin1("Adobe\u0000d\u0000\u0000\u0000\u0000\u0001"))
        val src = withSegmentsAfterSoi(JpegFixture.plain(), mpf, icc, adobe)
        val text = String(JpegStrip.strip(src)!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("ICC_PROFILE"))
        assertTrue(text.contains("Adobe"))
        assertFalse(text.contains("MPF"))
    }

    @Test fun `something that is not a well-formed JPEG gives null`() {
        assertNull(JpegStrip.strip(ByteArray(0)))
        assertNull(JpegStrip.strip("GIF89a not a jpeg".toByteArray()))
        assertNull("cut off before the pixels", JpegStrip.strip(fixture().copyOf(40)))
        assertNull(
            "a segment longer than the file",
            JpegStrip.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00, 0x01))
        )
    }
}
