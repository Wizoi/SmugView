package com.smugview.app.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 5-1 (design section 4): the words, exactly, and every failure names its cause and a way out. */
class OfflineMessagesTest {
    @Test fun `the texts are the ones the design lists`() {
        assertEquals("Saved on this phone", OfflineMessages.SAVED)
        assertEquals("Saving to this phone…", OfflineMessages.SAVING)
        assertEquals("Saving to this phone… 120 of 600", OfflineMessages.saving(120, 600))
        assertEquals("Waiting for a connection to save this photo.", OfflineMessages.WAITING_NETWORK)
        assertEquals("Will save on Wi-Fi. 3 of 40 saved so far.", OfflineMessages.waitingWifi(3, 40))
        assertEquals("SmugMug is busy. This photo will be saved automatically in a few minutes.", OfflineMessages.BUSY)
        assertEquals(
            "Not enough space on this phone to save more photos (needs about 12 MB, 340 MB free). Free up space and saving continues by itself.",
            OfflineMessages.storage(12L * 1024 * 1024, 340L * 1024 * 1024)
        )
        assertEquals("This photo was removed from SmugMug, so it can't be saved. Remove it from this collection.", OfflineMessages.GONE)
        assertEquals("SmugMug won't let this app download this photo. Open it once online, then try again.", OfflineMessages.FORBIDDEN)
        assertEquals("The download arrived damaged. Try again, or remove it from this collection.", OfflineMessages.DAMAGED)
        assertEquals("SmugMug answered this download with error 418. Try again later, or remove it from this collection.", OfflineMessages.unexpected(418))
        assertEquals("Videos play online only. The saved copy is a still picture.", OfflineMessages.VIDEO_STILL)
        assertEquals("Keep offline · 103 MB", OfflineMessages.keepOffline(103L * 1024 * 1024))
        assertEquals("Keep offline · size unknown", OfflineMessages.keepOffline(null))
        assertEquals(
            "Delete “Trip”? 12 photos saved on this phone (48 MB) will be removed. They stay on SmugMug.",
            OfflineMessages.deleteConfirm("Trip", 12, 48L * 1024 * 1024)
        )
        // The gone-gallery question (6-11c) says nothing about SmugMug: the gallery is not there any more.
        assertEquals(
            "Delete “Trip”? 12 photos saved on this phone (48 MB) will be removed.",
            OfflineMessages.removeGalleryConfirm("Trip", 12, 48L * 1024 * 1024)
        )
        assertEquals("You're offline. Showing the 7 photos saved on this phone.", OfflineMessages.offlineGallery(7))
        assertEquals("Try again", OfflineMessages.TRY_AGAIN)
        assertEquals("Remove", OfflineMessages.REMOVE)
    }

    @Test fun `the Q3 override texts say the size`() {
        assertEquals("Use mobile data too", OfflineMessages.USE_MOBILE_DATA_TOO)
        assertEquals("Waiting for Wi-Fi", OfflineMessages.WAITING_FOR_WIFI)
        assertEquals("Use mobile data (103 MB)", OfflineMessages.useMobileData(103L * 1024 * 1024))
        assertEquals("Use mobile data (size unknown)", OfflineMessages.useMobileData(null))
    }

    @Test fun `the lock text is the 4-8 one, unchanged`() {
        assertEquals(com.smugview.app.data.repository.AlbumLockedException.MESSAGE, OfflineMessages.LOCKED)
    }

    @Test fun `every reason has its own text that names a cause`() {
        val texts = FailureReason.values().associateWith { OfflineMessages.forFailure(it, httpCode = 418, needBytes = 5_000_000, freeBytes = 1_000_000) }
        for ((reason, text) in texts) {
            assertTrue("$reason has no text", text.isNotBlank())
            assertTrue("$reason: a bare 'Try again' is not a message: $text", text.length > "Try again".length + 20)
            assertFalse("$reason leaves a placeholder: $text", text.contains("{") || text.contains("}"))
        }
        assertEquals("every reason says something different", FailureReason.values().size, texts.values.toSet().size)
    }

    @Test fun `every permanent reason also says what to do`() {
        for (reason in listOf(FailureReason.GONE, FailureReason.DAMAGED, FailureReason.UNEXPECTED, FailureReason.FORBIDDEN, FailureReason.NO_SOURCE, FailureReason.LOCKED)) {
            val t = OfflineMessages.forFailure(reason, httpCode = 418)
            assertTrue("$reason gives no way out: $t", t.contains("Remove it") || t.contains("remove it") || t.contains("try again") || t.contains("Try again") || t.contains("Open it once"))
        }
    }

    @Test fun `sizes read as people read them`() {
        assertEquals("950 KB", OfflineMessages.size(950L * 1024))
        assertEquals("1 KB", OfflineMessages.size(10))
        assertEquals("103 MB", OfflineMessages.size(103L * 1024 * 1024))
        assertEquals("1.2 GB", OfflineMessages.size((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("size unknown", OfflineMessages.size(null))
        assertNotEquals(OfflineMessages.size(1024L * 1024), OfflineMessages.size(1023L * 1024))
    }

    @Test fun `an unexpected error without a code still reads`() {
        assertTrue(OfflineMessages.unexpected(null).startsWith("SmugMug answered this download with error unknown."))
    }
}
