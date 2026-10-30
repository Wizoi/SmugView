package com.smugview.app.data.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewGalleryContentTest {
    private fun g(n: String, key: String? = "K$n") = ListedGallery("node$n", n, null, null, key)

    @Test fun oneGalleryCarriesAlbumKeyAndTitle() {
        val c = NewGalleryContent.of("kevin", listOf(g("Spring")))
        assertEquals("New gallery on kevin", c.title)
        assertEquals("Spring", c.body)
        assertEquals("KSpring", c.userInfo[NewGalleryContent.KEY_ALBUM_KEY])
        assertEquals("Spring", c.userInfo[NewGalleryContent.KEY_ALBUM_TITLE])
        assertEquals("kevin", c.userInfo[NewGalleryContent.KEY_NICKNAME])
    }

    @Test fun severalGalleriesCarryOnlyTheNickname() {
        val c = NewGalleryContent.of("kevin", listOf(g("A"), g("B"), g("C"), g("D"), g("E")))
        assertEquals("5 new galleries on kevin", c.title)
        assertEquals("A, B, C and 2 more", c.body)
        assertEquals(mapOf(NewGalleryContent.KEY_NICKNAME to "kevin"), c.userInfo)
    }

    @Test fun oneGalleryWithoutAlbumKeyHasNoAlbumEntries() {
        val c = NewGalleryContent.of("kevin", listOf(g("A", key = null)))
        assertFalse(NewGalleryContent.KEY_ALBUM_KEY in c.userInfo)
    }

    @Test fun identifierIsPerSite() {
        assertEquals(
            NewGalleryContent.of("a", listOf(g("1"))).identifier,
            NewGalleryContent.of("a", listOf(g("2"), g("3"))).identifier
        )
        assertTrue(NewGalleryContent.identifierFor("a") != NewGalleryContent.identifierFor("b"))
    }

    @Test fun nextRunIsIntervalAfterNowAndNeverBelowMinimum() {
        assertEquals(1_000L + NextRunPolicy.DEFAULT_INTERVAL_MS, NextRunPolicy.earliestBeginMillis(1_000L))
        assertEquals(1_000L + NextRunPolicy.MIN_INTERVAL_MS, NextRunPolicy.earliestBeginMillis(1_000L, 5L))
    }

    @Test fun dueOnOpen() {
        val h = NextRunPolicy.DEFAULT_INTERVAL_MS
        assertTrue(NextRunPolicy.dueOnOpen(10 * h, null))
        assertFalse(NextRunPolicy.dueOnOpen(10 * h, 10 * h - 1))
        assertTrue(NextRunPolicy.dueOnOpen(10 * h, 9 * h))
    }
}
