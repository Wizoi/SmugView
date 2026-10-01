package com.smugview.app.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 step 4-0: the fake must answer the way SmugMug does, or a test over it proves nothing
 * (findings #2, #5, #8: every one of those was a fake that was kinder than the real thing). Each case
 * pins one fact that the live API was observed to do (design section 2, evidence P1-P13); the same
 * facts are checked against the live API by the gated `LiveApiContractTest`.
 */
class FakeRealismTest {
    private val server = FakeSmugMugServer()

    private fun get(path: String): Response =
        server.handle(Request.Builder().url("https://api.smugmug.com/api/v2/$path").build())

    private fun obj(path: String): JsonObject = JsonParser.parseString(get(path).body!!.string()).asJsonObject
    private fun response(path: String): JsonObject = obj(path).getAsJsonObject("Response")

    private fun echo(path: String): String = response(path).get("Uri").asString.substringAfter('?', "")

    @Test fun uri_echo_drops_password_apikey_expand_and_verbosity() {
        val q = echo("node/4zqWw!children?APIKey=<KEY>&Password=hunter2&count=10&_expand=HighlightImage&_verbosity=1&_filter=Name")
        assertEquals("count=10&_filter=Name", q)
    }

    @Test fun uri_echo_drops_params_the_endpoint_does_not_accept() {
        // user!topkeywords accepts NodeURI, not NodeID (Q6): NodeID must not be echoed.
        assertEquals("", echo("user/idzifamily!topkeywords?APIKey=<KEY>&NodeID=P4BKB&_verbosity=1"))
        assertEquals(
            "NodeURI=%2Fapi%2Fv2%2Fnode%2FP4BKB",
            echo("user/idzifamily!topkeywords?NodeURI=/api/v2/node/P4BKB")
        )
    }

    @Test fun next_page_is_the_echo_plus_start_and_drops_expand_and_verbosity() {
        server.addGalleries("3BxbFF", 20)
        val pages = response("node/3BxbFF!children?count=5&_expand=HighlightImage&_verbosity=1&_filter=Name").getAsJsonObject("Pages")
        val next = pages.get("NextPage").asString
        assertEquals("/api/v2/node/3BxbFF!children?count=5&_filter=Name&start=6", next)
        assertFalse(next.contains("_expand"))
        assertFalse(next.contains("_verbosity"))
    }

    @Test fun expansions_appear_only_when_expand_asks_for_them() {
        server.listBigFolder()
        val plain = obj("node/${FakeSmugMugServer.BIG_FOLDER}!children?count=3&_verbosity=1")
        assertNull(plain.get("Expansions"))
        val expanded = obj("node/${FakeSmugMugServer.BIG_FOLDER}!children?count=3&_expand=HighlightImage&_verbosity=1")
        assertNotNull(expanded.getAsJsonObject("Expansions"))
        // keyed by the Uri the node lists under Uris.HighlightImage
        val node = expanded.getAsJsonObject("Response").getAsJsonArray("Node")[0].asJsonObject
        val key = node.getAsJsonObject("Uris").get("HighlightImage").asString
        assertNotNull(expanded.getAsJsonObject("Expansions").get(key))
    }

    @Test fun uris_are_link_objects_without_verbosity_and_strings_with_it() {
        val withV = response("node/4zqWw!children?_verbosity=1&_filteruri=ChildNodes").getAsJsonArray("Node")[0].asJsonObject
        assertTrue(withV.getAsJsonObject("Uris").get("ChildNodes").isJsonPrimitive)
        val without = response("node/4zqWw!children?_filteruri=ChildNodes").getAsJsonArray("Node")[0].asJsonObject
        assertTrue(without.getAsJsonObject("Uris").get("ChildNodes").isJsonObject)
    }

    @Test fun uris_only_lists_what_filteruri_asks_for() {
        val node = response("node/4zqWw!children?_verbosity=1&_filteruri=ChildNodes").getAsJsonArray("Node")[0].asJsonObject
        assertEquals(setOf("ChildNodes"), node.getAsJsonObject("Uris").keySet())
    }

    @Test fun children_page_is_capped_at_200_and_pages_by_start() {
        server.addGalleries("3BxbFF", 269)
        val p1 = response("node/3BxbFF!children?count=500&start=1").getAsJsonObject("Pages")
        assertEquals(200, p1.get("Count").asInt)
        assertEquals(270, p1.get("Total").asInt)
        val p2 = response("node/3BxbFF!children?count=500&start=201").getAsJsonObject("Pages")
        assertEquals(70, p2.get("Count").asInt)
        assertNull(p2.get("NextPage"))
    }

    @Test fun the_big_gallery_is_the_150th_child() {
        server.listBigFolder()
        val page = response("node/${FakeSmugMugServer.BIG_FOLDER}!children?count=100&start=101&_verbosity=1")
        assertEquals(50, page.getAsJsonObject("Pages").get("Count").asInt)
        val last = page.getAsJsonArray("Node").last().asJsonObject
        assertEquals(FakeSmugMugServer.BIG_GALLERY_NODE, last.get("NodeID").asString)
        assertEquals("/api/v2/album/${FakeSmugMugServer.BIG_GALLERY_ALBUM}", last.getAsJsonObject("Uris").get("Album").asString.let {
            it.substringBefore('?')
        })
        // AlbumKey != NodeID
        assertNotEquals(FakeSmugMugServer.BIG_GALLERY_ALBUM, FakeSmugMugServer.BIG_GALLERY_NODE)
    }

    @Test fun albums_page_is_capped_at_100() {
        server.albums = (1..230).map {
            FakeSmugMugServer.FakeAlbum(
                "Ak%04d".format(it), "Nx%04d".format(it), "G$it", "/Pub/G$it", server.daysAgo(3), server.daysAgo(3)
            )
        }
        val pages = response("user/idzifamily!albums?count=500&start=1").getAsJsonObject("Pages")
        assertEquals(100, pages.get("Count").asInt)
        assertEquals(230, pages.get("Total").asInt)
        assertTrue(pages.get("NextPage").asString.endsWith("start=101"))
    }

    @Test fun images_page_is_capped_at_500_and_big_gallery_has_videos_where_expected() {
        val album = FakeSmugMugServer.BIG_GALLERY_ALBUM
        val pages = response("album/$album!images?count=1000&_expand=LargestVideo").getAsJsonObject("Pages")
        assertEquals(500, pages.get("Count").asInt)
        assertEquals(620, pages.get("Total").asInt)
        val images = response("album/$album!images?count=1000&_expand=LargestVideo&_verbosity=1").getAsJsonArray("AlbumImage")
        assertEquals("MP4", images[2].asJsonObject.get("Format").asString)
        assertEquals("JPG", images[0].asJsonObject.get("Format").asString)
        // the expansion exists only with _expand=LargestVideo
        assertNull(obj("album/$album!images?count=10&_verbosity=1").get("Expansions"))
        assertNotNull(obj("album/$album!images?count=10&_expand=LargestVideo&_verbosity=1").get("Expansions"))
    }

    @Test fun image_search_reports_at_most_10000_and_answers_an_empty_500_past_the_window() {
        server.imageSearchTotal = 25_000
        val p = response("image!search?Text=kentridge&count=100&start=1").getAsJsonObject("Pages")
        assertEquals(10_000, p.get("Total").asInt)
        assertEquals(100, p.get("Count").asInt)
        assertEquals(200, get("image!search?Text=kentridge&count=100&start=9901").code)
        val past = get("image!search?Text=kentridge&count=100&start=9902")
        assertEquals(500, past.code)
        assertTrue(past.body!!.contentType().toString().startsWith("text/html"))
        assertEquals("", past.body!!.string())
    }

    @Test fun a_locked_gallery_answers_200_with_no_images_and_response_level_password() {
        server.gateImages = true
        val locked = response("album/FfHCms!images?count=100")
        assertNull(locked.get("AlbumImage"))
        assertEquals(0, locked.getAsJsonObject("Pages").get("Total").asInt)
        assertEquals("Password", response("album/FfHCms").getAsJsonObject("Album").get("ResponseLevel").asString)
        // the public gallery is unaffected
        assertEquals("Public", response("album/N74KSK").getAsJsonObject("Album").get("ResponseLevel").asString)
    }

    @Test fun image_by_key_redirects_to_dash_zero_with_no_store() {
        val r = get("image/FfHCmsi001?_verbosity=1")
        assertEquals(301, r.code)
        assertEquals("/api/v2/image/FfHCmsi001-0?_verbosity=1", r.header("Location"))
        assertTrue(r.header("Cache-Control")!!.contains("no-store"))
        assertEquals(200, get("image/FfHCmsi001-0").code)
    }

    @Test fun topkeywords_is_scoped_by_node_uri_only() {
        val site = response("user/idzifamily!topkeywords").getAsJsonObject("UserTopKeywords").getAsJsonArray("TopKeywords")
        assertEquals(5, site.size())
        val scoped = response("user/idzifamily!topkeywords?NodeURI=/api/v2/node/P4BKB")
            .getAsJsonObject("UserTopKeywords").getAsJsonArray("TopKeywords")
        assertEquals(2, scoped.size())
        // NodeID is ignored, like the live API: the answer is the whole-site list
        val ignored = response("user/idzifamily!topkeywords?NodeID=P4BKB").getAsJsonObject("UserTopKeywords").getAsJsonArray("TopKeywords")
        assertEquals(5, ignored.size())
    }

    @Test fun every_answer_carries_the_no_store_header() {
        assertEquals("private, no-store, no-cache, max-age=0", get("node/4zqWw!children").header("Cache-Control"))
    }

    @Test fun unlock_sets_the_two_session_cookies() {
        val r = server.handle(
            Request.Builder().url("https://api.smugmug.com/api/v2/node/2sDN5x!unlock")
                .post(okhttp3.FormBody.Builder().add("Password", "x").build()).build()
        )
        assertEquals(200, r.code)
        val names = r.headers("Set-Cookie").map { it.substringBefore('=') }.toSet()
        assertEquals(setOf("shm", "SMSESS"), names)
        assertTrue(r.headers("Set-Cookie").all { it.contains("Secure") && it.contains("HttpOnly") })
        assertTrue(server.hasSession())
    }

    // --- phase 5 step 5-0: the archived original (design P1, P6, P7) ---

    @Test fun archived_fields_appear_only_when_filter_lists_them_and_describe_the_cdn_body() {
        val full = response("album/FfHCms!images?count=2&_filter=ImageKey,ArchivedUri,ArchivedSize,ArchivedMD5&_verbosity=1")
            .getAsJsonArray("AlbumImage")[0].asJsonObject
        val key = full.get("ImageKey").asString
        assertEquals(FakeOriginals.archivedUri(key), full.get("ArchivedUri").asString)
        assertEquals(FakeOriginals.size(key), full.get("ArchivedSize").asLong)
        assertEquals(FakeOriginals.md5(key), full.get("ArchivedMD5").asString)
        assertTrue(full.get("ArchivedUri").asString.matches(Regex("""https://photos\.smugmug\.com/photos/i-$key/0/[0-9a-f]{8}/D/i-$key-D\.jpg""")))
        // a filter without them leaves them out, like the live API (the app must ask for them)
        val slim = response("album/FfHCms!images?count=2&_filter=ImageKey,Title&_verbosity=1").getAsJsonArray("AlbumImage")[0].asJsonObject
        assertFalse(slim.has("ArchivedUri") || slim.has("ArchivedSize") || slim.has("ArchivedMD5"))
        // the by-key route answers them too, and an unknown key is a 404 (P6)
        val byKey = response("image/${key}-0?_filter=ImageKey,ArchivedUri,ArchivedSize,ArchivedMD5,Format,IsVideo").getAsJsonObject("Image")
        assertEquals(FakeOriginals.md5(key), byKey.get("ArchivedMD5").asString)
        assertEquals(404, get("image/nonexistent-0?_filter=ArchivedUri").code)
    }

    @Test fun a_videos_archived_original_is_a_jpeg_still_not_the_mp4() {
        val album = FakeSmugMugServer.BIG_GALLERY_ALBUM
        val video = response("album/$album!images?count=5&_filter=ImageKey,Format,IsVideo,ArchivedUri,ArchivedSize&_verbosity=1")
            .getAsJsonArray("AlbumImage")[2].asJsonObject
        assertEquals("MP4", video.get("Format").asString)
        assertTrue(video.get("IsVideo").asBoolean)
        assertTrue(video.get("ArchivedUri").asString.endsWith("-D.jpg"))
        assertEquals(164_821L, video.get("ArchivedSize").asLong)
    }

    @Test fun fake_cdn_serves_the_bytes_the_api_described_with_etag_range_and_404() {
        FakeCdn().use { cdn ->
            val key = "FfHCmsi001"
            val client = cdn.clientOver(okhttp3.OkHttpClient())
            val url = FakeOriginals.archivedUri(key)
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                assertEquals(200, r.code)
                val body = r.body!!.bytes()
                assertEquals(FakeOriginals.size(key), body.size.toLong())
                assertEquals(FakeOriginals.md5(key), java.security.MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it) })
                assertEquals("\"" + FakeOriginals.md5(key) + "\"", r.header("ETag"))
            }
            client.newCall(Request.Builder().url(url).header("Range", "bytes=100-").build()).execute().use { r ->
                assertEquals(206, r.code)
                assertEquals(FakeOriginals.size(key) - 100, r.body!!.bytes().size.toLong())
            }
            cdn.gone += key
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                assertEquals(404, r.code)
                assertEquals("no-store", r.header("Cache-Control"))
            }
        }
    }

    // Phase 6-0 (design L2, L4, L6): the shapes the phase-6 steps are written against.

    @Test fun a_locked_folder_answers_the_configured_code_and_its_public_sibling_is_listed_after_it() {
        server.listMemories()
        val memories = response("node/6KHfpq!children").getAsJsonArray("Node").map { it.asJsonObject }
        assertEquals(listOf("sNWzhX", "Tn8Vc3"), memories.map { it.get("NodeID").asString })
        assertEquals("Password", memories[0].get("SecurityType").asString)
        assertEquals("the live answer (L2) is the default", 404, get("node/sNWzhX!children").code)
        server.lockedChildrenCode = 401
        assertEquals("a test of the old shape can ask for 401", 401, get("node/sNWzhX!children").code)
        assertEquals(200, get("node/Tn8Vc3!children").code)
        assertTrue("the root lists Memories after listMemories()", response("node/4zqWw!children").getAsJsonArray("Node")
            .any { it.asJsonObject.get("NodeID").asString == "6KHfpq" })
    }

    @Test fun recent_images_include_one_family_events_photo_and_the_bio_image_comes_with_its_expansion() {
        val recent = response("user/idzifamily!recentimages?count=25").getAsJsonArray("Image").map { it.asJsonObject }
        assertEquals(listOf("XVRvVTM", "ttDKqjt"), recent.map { it.get("ImageKey").asString })
        assertTrue(recent[1].get("ThumbnailUrl").asString.contains("/Family/Events/"))
        assertFalse("no WebUri on recent images (P4)", recent[1].has("WebUri"))
        val body = obj("user/idzifamily?_expand=BioImage&_verbosity=1")
        val bio = body.getAsJsonObject("Expansions").entrySet().first().value.asJsonObject.getAsJsonObject("BioImage")
        assertTrue(bio.get("ThumbnailUrl").asString.contains("/0/Th/"))
        assertFalse("site B has no BioImage", obj("user/siteb?_expand=BioImage&_verbosity=1").has("Expansions"))
    }

    @Test fun album_images_carry_WebUri_only_when_the_filter_asks_for_it() {
        val without = response("album/N74KSK!images?count=1&_filter=ImageKey,ThumbnailUrl").getAsJsonArray("AlbumImage")[0].asJsonObject
        assertFalse(without.has("WebUri"))
        val with = response("album/N74KSK!images?count=1&_filter=ImageKey,WebUri").getAsJsonArray("AlbumImage")[0].asJsonObject
        assertEquals("https://gallery.idzifamily.com/Kentridge/Public/i-N74KSKi001", with.get("WebUri").asString)
    }
}
