package com.smugview.app.data.notify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewGalleryNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private fun g(id: String, name: String) = ListedGallery(id, name, null, null, albumKey = "k$id")

    @Test fun `the text names the site and the galleries`() {
        assertEquals("New gallery on idzifamily", NewGalleryText.title("idzifamily", 1))
        assertEquals("4 new galleries on idzifamily", NewGalleryText.title("idzifamily", 4))
        assertEquals("A", NewGalleryText.body(listOf("A")))
        assertEquals("A, B, C and 2 more", NewGalleryText.body(listOf("A", "B", "C", "D", "E")))
    }

    @Test fun `new galleries become one notification per site that the next run replaces`() {
        val notifier = NewGalleryNotifier(app)
        assertTrue(runBlocking { notifier.announce("idzifamily", listOf(g("n1", "2026-10-02 - Meet"))) })
        assertTrue(runBlocking { notifier.announce("idzifamily", listOf(g("n2", "2026-10-03 - Dance"), g("n3", "Band"))) })
        assertEquals(1, manager.allNotifications.size)
        assertEquals("2 new galleries on idzifamily", manager.allNotifications.single().extras.getString("android.title"))
    }

    @Test fun `with notifications off nothing is posted and the caller is told so`() {
        manager.setNotificationsEnabled(false)
        assertFalse(runBlocking { NewGalleryNotifier(app).announce("idzifamily", listOf(g("n1", "Meet"))) })
        assertEquals(0, manager.allNotifications.size)
    }
}
