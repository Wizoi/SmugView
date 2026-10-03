package com.smugview.app.data.notify

import com.smugview.app.data.repository.FakeSmugMugServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NewestGalleriesTest {
    @Test fun `the poll asks for one page ordered by date added and bypasses the cache`() {
        val server = FakeSmugMugServer()
        val listed = runBlocking { NewestGalleries(server.api(), "key")("idzifamily") }
        val request = server.albumsRequests().single()
        assertEquals("Date Added (Descending)", request.url.queryParameter("Order"))
        assertEquals("100", request.url.queryParameter("count"))
        assertEquals("no-cache", request.header("Cache-Control"))
        assertFalse("one page only", request.url.queryParameterNames.contains("start"))
        assertEquals(listOf("LCdk7F", "sXQz4G"), listed.map { it.nodeId })
    }

    @Test fun `a gallery is identified by NodeID, not AlbumKey, and keeps its page address`() {
        val server = FakeSmugMugServer()
        val first = runBlocking { NewestGalleries(server.api(), "key")("idzifamily") }.first()
        assertEquals("LCdk7F", first.nodeId)
        assertEquals("FfHCms", first.albumKey)
        assertFalse("AlbumKey FfHCms is not the id", first.nodeId == "FfHCms")
        assertEquals("/Family/School/2026-09-01--New-School-Year", first.urlPath)
        assertEquals("https://gallery.idzifamily.com/Family/School/2026-09-01--New-School-Year", first.webUri)
    }
}
