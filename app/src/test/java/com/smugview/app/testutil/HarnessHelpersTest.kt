package com.smugview.app.testutil

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Step 6-0: the test helpers do what the later steps rely on (green on the old code). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HarnessHelpersTest {
    @Test fun `the JPEG fixture carries orientation, serial, owner, artist, GPS and an XMP segment`() {
        val dir = java.nio.file.Files.createTempDirectory("jpeg-fixture").toFile()
        try {
            val bytes = JpegFixture.withPrivateData(dir)
            val file = java.io.File(dir, "read.jpg").also { it.writeBytes(bytes) }
            val exif = ExifInterface(file.absolutePath)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals(JpegFixture.SERIAL, exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
            assertEquals(JpegFixture.OWNER, exif.getAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME))
            assertEquals(JpegFixture.ARTIST, exif.getAttribute(ExifInterface.TAG_ARTIST))
            assertNotNull("GPS present", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue("XMP APP1 present", JpegFixture.hasXmp(bytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `UncaughtCapture records an exception thrown on a background thread and restores the handler`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        UncaughtCapture().use { cap ->
            val t = Thread { throw IllegalStateException("boom") }
            t.start(); t.join()
            assertEquals(listOf("boom"), cap.captured.map { it.message })
        }
        assertEquals(before, Thread.getDefaultUncaughtExceptionHandler())
    }
}
