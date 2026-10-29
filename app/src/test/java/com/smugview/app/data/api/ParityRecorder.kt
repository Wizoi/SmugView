package com.smugview.app.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume
import org.junit.Test
import retrofit2.Retrofit
import com.smugview.app.data.api.apiConverterFactory
import java.io.File
import java.util.Properties

/**
 * iOS spike (Stage 0, step 1): records real SmugMug answers for every GET the app makes, with the app's own request shapes (the typed
 * [SmugMugApi] and its defaults), so [ModelParityTest] can check them against the models. Anonymous and read-only: nothing under a
 * password folder, no unlock. Opt-in (`PARITY_RECORD=1`), live network, never part of a normal run.
 *
 * Files: `src/test/resources/parity/<method>__<label>.json`. The API key is replaced by `TESTKEY` in every saved body.
 */
class ParityRecorder {
    private val out = File("src/test/resources/parity")
    private val report = mutableListOf<String>()
    @Volatile private var current: String? = null
    private lateinit var key: String

    @Test fun record() {
        Assume.assumeTrue("set PARITY_RECORD=1 to record", System.getenv("PARITY_RECORD") == "1")
        val props = Properties().apply { File("../local.properties").inputStream().use { load(it) } }
        key = props.getProperty("smugmug.api.key")
        val nick = props.getProperty("smugmug.nickname")
        out.mkdirs()
        out.listFiles()?.forEach { it.delete() }

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request().newBuilder().header("Accept", "application/json").build())
                val name = current
                val text = response.body?.string().orEmpty()
                if (name != null) File(out, "$name.json").writeText(text.replace(key, "TESTKEY"))
                response.newBuilder().body(text.toResponseBody("application/json".toMediaTypeOrNull())).build()
            })
            .build()
        val api = Retrofit.Builder().baseUrl("https://api.smugmug.com/api/v2/").client(client)
            .addConverterFactory(apiConverterFactory()).build().create(SmugMugApi::class.java)

        fun <T> rec(method: String, label: String, call: suspend () -> T): JsonObject? {
            current = "${method}__$label"
            try {
                runBlocking { call() }
                report += "ok     $method $label"
            } catch (e: Exception) {
                report += "FAILED $method $label: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
            val f = File(out, "$current.json")
            current = null
            Thread.sleep(250)
            return if (f.exists()) runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull() else null
        }

        fun JsonElement?.str(vararg path: String): String? {
            var e: JsonElement? = this
            for (p in path) e = (e as? JsonObject)?.get(p)
            return if (e == null || e.isJsonNull) null else if (e.isJsonObject) e.asJsonObject.get("Uri")?.asString else e.asString
        }

        val profile = rec("getUserProfile", "site") { api.getUserProfile(nick, key, ignoreErrors = "true") }
        rec("getUserBioImage", "site") { api.getUserBioImage(nick, key) }
        val rootId = profile.str("Response", "User", "Uris", "Node")?.substringAfterLast('/')
            ?: error("no root node in the profile")

        val rootKids = rec("getNodeChildren", "root") { api.getNodeChildren(rootId, key, count = 100, start = 1) }
        val nodes = rootKids?.getAsJsonObject("Response")?.getAsJsonArray("Node")?.map { it.asJsonObject }.orEmpty()
        val folder = nodes.firstOrNull { it.get("Type")?.asString == "Folder" && it.get("SecurityType")?.asString != "Password" }
        folder?.get("NodeID")?.asString?.let { fid -> rec("getNodeChildren", "folder") { api.getNodeChildren(fid, key) } }
        rec("getNode", "root") { api.getNode(rootId, key, expand = "HighlightImage", ignoreErrors = "true") }

        val albums = rec("getUserAlbums", "page1") { api.getUserAlbums(nick, key, count = 20, start = 1) }
        val firstAlbum = albums?.getAsJsonObject("Response")?.getAsJsonArray("Album")?.firstOrNull { it.asJsonObject.get("SecurityType")?.asString != "Password" }?.asJsonObject
        rec("getNewestAlbums", "page1") { api.getNewestAlbums(nick, key, count = 20) }
        val albumKey = firstAlbum?.get("AlbumKey")?.asString
        val albumNodeId = firstAlbum?.get("NodeID")?.asString
        if (albumNodeId != null) {
            rec("getNode", "album") { api.getNode(albumNodeId, key, expand = "HighlightImage", ignoreErrors = "true") }
            rec("getNodeParents", "album") { api.getNodeParents(albumNodeId, key, ignoreErrors = "true") }
        }
        if (albumKey != null) {
            rec("getAlbum", "public") { api.getAlbum(albumKey, key, ignoreErrors = "true") }
            val imgs = rec("getAlbumImages", "page1") { api.getAlbumImages(albumKey, key, count = 20, start = 1) }
            val first = imgs?.getAsJsonObject("Response")?.getAsJsonArray("AlbumImage")?.firstOrNull()?.asJsonObject
            first?.get("ImageKey")?.asString?.let { ik ->
                rec("getImage", "photo") { api.getImage("$ik-0", key) }
                rec("getImageSource", "photo") { api.getImageSource("$ik-0", key) }
                rec("getImageExif", "photo") { api.getImageExif("$ik-0", key) }
                rec("getImageSizeDetailsByUri", "photo") { api.getImageSizeDetailsByUri("/api/v2/image/$ik-0!sizedetails", key) }
            }
        }

        // A video, found by search (the shape that broke searched videos on 2026-10-29).
        val concert = rec("searchImages", "videos") { api.searchImages(key, scope = "/api/v2/node/$rootId", text = "concert", count = 10) }
        val video = concert?.getAsJsonObject("Response")?.getAsJsonArray("Image")?.firstOrNull { it.asJsonObject.get("Format")?.asString == "MP4" }?.asJsonObject
        video?.get("ImageKey")?.asString?.let { vk -> rec("getImage", "video") { api.getImage("$vk-0", key) } }
        rec("searchImages", "photos") { api.searchImages(key, scope = "/api/v2/node/$rootId", text = "kentridge", count = 10) }
        rec("getImagesByKeyword", "tag") { api.getImagesByKeyword(key, scope = "/api/v2/user/$nick", text = "kentridge", count = 10) }
        rec("searchNodes", "meet") { api.searchNodes(key, scope = "/api/v2/node/$rootId", text = "meet") }
        rec("searchUsers", "idzi") { api.searchUsers(key, "idzi") }
        rec("getUserRecentImages", "site") { api.getUserRecentImages(nick, key) }
        rec("getUserTopKeywords", "site") { api.getUserTopKeywords(nick, key, nodeUri = "/api/v2/node/$rootId") }

        File(out, "_report.txt").writeText(report.joinToString("\n") + "\n")
        println(report.joinToString("\n"))
    }
}
