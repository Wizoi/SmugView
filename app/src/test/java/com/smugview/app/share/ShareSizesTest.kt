package com.smugview.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size prompt (owner, 2026-11-06): Mobile size or Original size, asked every time. An original that is not a JPEG, or very large, is
 * still offered, with a warning; a video never gets an Original (its ArchivedUri is a JPEG still, not the video: CLAUDE.md).
 */
class ShareSizesTest {
    @Test fun `a JPEG original is offered with its size and no warning`() {
        val c = ShareSizes.choices(isVideo = false, format = "JPG", archivedSize = 4_200_000L, hasOriginalSource = true)
        assertTrue(c.originalOffered)
        assertEquals(4_200_000L, c.originalBytes)
        assertEquals(ShareSizes.OriginalNote.None, c.note)
    }

    @Test fun `a non JPEG original is offered with the warning that camera data stays in`() {
        for (format in listOf("PNG", "HEIC", "GIF", "TIFF", "png")) {
            val c = ShareSizes.choices(isVideo = false, format = format, archivedSize = 3_000_000L, hasOriginalSource = true)
            assertTrue(format, c.originalOffered)
            assertEquals(format, ShareSizes.OriginalNote.KeepsCameraData, c.note)
        }
    }

    @Test fun `a very large JPEG original is offered with a large file note`() {
        val c = ShareSizes.choices(isVideo = false, format = "JPG", archivedSize = ShareSizes.LARGE_BYTES + 1, hasOriginalSource = true)
        assertTrue(c.originalOffered)
        assertEquals(ShareSizes.OriginalNote.Large, c.note)
    }

    @Test fun `a large non JPEG original shows the camera data warning first`() {
        val c = ShareSizes.choices(isVideo = false, format = "PNG", archivedSize = ShareSizes.LARGE_BYTES * 3, hasOriginalSource = true)
        assertEquals(ShareSizes.OriginalNote.KeepsCameraData, c.note)
    }

    @Test fun `a video never offers an original`() {
        val c = ShareSizes.choices(isVideo = true, format = "MP4", archivedSize = 90_000_000L, hasOriginalSource = true)
        assertFalse(c.originalOffered)
    }

    @Test fun `no original source means no original`() {
        val c = ShareSizes.choices(isVideo = false, format = "JPG", archivedSize = null, hasOriginalSource = false)
        assertFalse(c.originalOffered)
    }

    @Test fun `an unknown size is still offered, without a number`() {
        val c = ShareSizes.choices(isVideo = false, format = "JPG", archivedSize = null, hasOriginalSource = true)
        assertTrue(c.originalOffered)
        assertEquals(null, c.originalBytes)
        assertEquals("", ShareSizes.sizeText(null))
    }

    @Test fun `sizes read as megabytes with one decimal, small ones as kilobytes`() {
        assertEquals("4.2 MB", ShareSizes.sizeText(4_200_000L))
        assertEquals("428 KB", ShareSizes.sizeText(428_000L))
        assertEquals("38.0 MB", ShareSizes.sizeText(38_000_000L))
    }

    @Test fun `an unknown format counts as not a JPEG`() {
        assertEquals(ShareSizes.OriginalNote.KeepsCameraData, ShareSizes.choices(false, null, 1_000L, true).note)
    }

    // The photo details ask for OriginalSize (the raw byte size of the original) but not ArchivedSize, so the prompt showed no size on Android.
    @Test fun `the original size comes from ArchivedSize, else OriginalSize`() {
        assertEquals(4_200_000L, ShareSizes.knownBytes(archivedSize = 4_200_000L, originalSize = 9L))
        assertEquals(4_200_000L, ShareSizes.knownBytes(archivedSize = null, originalSize = 4_200_000L))
        assertEquals(null, ShareSizes.knownBytes(archivedSize = null, originalSize = null))
        assertEquals("a zero is not a size", null, ShareSizes.knownBytes(archivedSize = 0L, originalSize = null))
    }
}
