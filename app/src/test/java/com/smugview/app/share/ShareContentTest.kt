package com.smugview.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 6-14: what a share may hand out (design 3.10, R-53). */
class ShareContentTest {
    private val gallery = "https://idzifamily.smugmug.com/Family/School/Fall-2025"
    private val archived = "https://photos.smugmug.com/photos/i-AbC123/0/abcd1234/D/i-AbC123-D.jpg"

    @Test fun `a photo with its own web page shares that page`() {
        assertEquals("$gallery/i-AbC123", ShareContent.photoLink("$gallery/i-AbC123", null, "AbC123"))
    }

    @Test fun `a photo from search has no page of its own and gets the gallery page plus its key`() {
        assertEquals("$gallery/i-AbC123", ShareContent.photoLink(null, gallery, "AbC123"))
        assertEquals("$gallery/i-AbC123", ShareContent.photoLink(null, "$gallery/", "AbC123"))
    }

    @Test fun `no page and no gallery means no link`() {
        assertNull(ShareContent.photoLink(null, null, "AbC123"))
        assertNull(ShareContent.photoLink("", "  ", "AbC123"))
        assertNull(ShareContent.photoLink(null, gallery, ""))
    }

    @Test fun `a photos dot smugmug dot com address is never a link, whichever field it is in`() {
        assertNull(ShareContent.photoLink(archived, null, "AbC123"))
        assertNull(ShareContent.photoLink(null, archived, "AbC123"))
        assertEquals("$gallery/i-AbC123", ShareContent.photoLink(archived, gallery, "AbC123"))
        assertFalse(ShareContent.isShareable("https://PHOTOS.smugmug.com/x"))
        assertFalse(ShareContent.isShareable("https://cdn.photos.smugmug.com/x"))
    }

    @Test fun `only https is shareable`() {
        assertFalse(ShareContent.isShareable("http://idzifamily.smugmug.com/Family"))
        assertFalse(ShareContent.isShareable("file:///data/x.jpg"))
        assertFalse(ShareContent.isShareable("javascript:alert(1)"))
        assertFalse(ShareContent.isShareable("https://idzifamily.smugmug.com/a b"))
        assertFalse(ShareContent.isShareable(null))
        assertTrue(ShareContent.isShareable("https://idzifamily.smugmug.com/Family"))
        assertTrue(ShareContent.isShareable("https://www.smugmug.com/photos/photos.smugmug.com"))
    }

    @Test fun `a photo's album uri gives the album key`() {
        assertEquals("kZ9xQ2", ShareContent.albumKeyOf("/api/v2/album/kZ9xQ2"))
        assertEquals("kZ9xQ2", ShareContent.albumKeyOf("/api/v2/album/kZ9xQ2?_verbosity=1"))
        assertNull(ShareContent.albumKeyOf("/api/v2/node/abc"))
        assertNull(ShareContent.albumKeyOf(null))
    }
}
