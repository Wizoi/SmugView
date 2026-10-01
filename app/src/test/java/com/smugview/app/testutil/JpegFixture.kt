package com.smugview.app.testutil

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phase 6-0: a small JPEG that carries the private data a share must strip (design 3.10, R-53): an APP1
 * Exif with `Orientation=6`, `BodySerialNumber`, `CameraOwnerName` (androidx ExifInterface 1.3.6 cannot write `LensSerialNumber`), `Artist` and GPS, plus an XMP APP1
 * (a second APP1 segment). Pixels come from `ImageIO`, the Exif block from the framework `ExifInterface`,
 * and the XMP segment is spliced in by hand.
 */
object JpegFixture {
    const val SERIAL = "SN-4821907"
    const val OWNER = "Kevin Example (owner)"
    const val ARTIST = "Kevin Example"
    const val XMP_MARKER = "http://ns.adobe.com/xap/1.0/"

    /** Plain pixels, no metadata: an 8x8 JPEG (Robolectric's Bitmap.compress encodes a real JPEG). */
    fun plain(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) bmp.setPixel(x, y, (0xFF shl 24) or (x * 30 shl 16) or (y * 30 shl 8) or 0x80)
        return ByteArrayOutputStream().also { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "compress failed" } }.toByteArray()
    }

    /** [plain] with the Exif block and an XMP APP1 added. */
    fun withPrivateData(dir: File): ByteArray {
        val file = File.createTempFile("fixture", ".jpg", dir)
        try {
            file.writeBytes(plain())
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, SERIAL)
                setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, OWNER)
                setAttribute(ExifInterface.TAG_ARTIST, ARTIST)
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "43/1,2/1,53/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "76/1,8/1,50/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                saveAttributes()
            }
            return insertXmp(file.readBytes())
        } finally {
            file.delete()
        }
    }

    /** Splices an XMP APP1 segment right after SOI (before every other segment). */
    private fun insertXmp(jpeg: ByteArray): ByteArray {
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val xmp = "$XMP_MARKER\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF/></x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
        val len = xmp.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + xmp
        return jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)
    }

    /** Whether [jpeg] contains an XMP APP1 segment. */
    fun hasXmp(jpeg: ByteArray): Boolean = String(jpeg, Charsets.ISO_8859_1).contains(XMP_MARKER)
}
