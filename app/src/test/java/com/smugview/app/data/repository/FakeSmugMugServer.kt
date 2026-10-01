package com.smugview.app.data.repository

import com.smugview.app.data.api.SmugMugApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixture "F" from docs/design/phase-2-tree-and-dot.md section 6, served as an OkHttp interceptor.
 *
 * Topology (synthetic; the IDs are real shapes, AlbumKey != NodeID):
 *   4zqWw (site root) -> 2sDN5x Family (SecurityType Password)
 *                          -> P4BKB School (SecurityType "None", EffectiveSecurityType "Password")
 *                               -> gallery NodeID LCdk7F / AlbumKey FfHCms
 *                     -> 3BxbFF (public) -> gallery NodeID sXQz4G / AlbumKey N74KSK
 *
 * `!parents` is SELF FIRST and anonymous, like the live API (design V6, V7). Every request is
 * recorded in [requests] as "METHOD path" (query and body are not recorded, so no secret can
 * land in it).
 *
 * Phase 4 step 4-0 realism (design section 5, evidence in findings.md): `Response.Uri` echoes only the
 * parameters SmugMug accepts ([AcceptedParams]); `NextPage` is built the SmugMug way (the echo plus
 * `start`, so it drops `_expand` and `_verbosity`); `Expansions` appear only when `_expand` asks;
 * `Uris` only when `_filteruri` lists them, as strings only with `_verbosity=1`; page caps are the live
 * ones (children 200, albums 100, images 500, search 100); `image!search` stops at 10,000 and answers
 * an empty 500 past that window; `image/{key}` 301s to `{key}-0`; `user!topkeywords` honours `NodeURI`
 * only; every answer carries SmugMug's `Cache-Control: private, no-store, no-cache, max-age=0`.
 * [handle] is the whole fake (gates, recording, routing): [LoopbackSmugMug] serves it on a socket.
 *
 * Phase 3 additions (synthetic IDs, real shapes and lengths, NodeID != AlbumKey):
 *   site B, nickname `siteb`: Rb7Tq2 (root) -> Hq2Lm9 (public folder) -> gallery NodeID Vt4Kp8 / AlbumKey jX9wQe
 *   a second password root on site A, Wq8Rz3 (SecurityType Password), reachable by id and listed
 *   under the root only after [listSecondPasswordRoot]
 */
class FakeSmugMugServer {

    /**
     * A request held inside the interceptor (an OkHttp thread) until [release]. [awaitArrived] says
     * whether the first matching request reached the fake in time.
     */
    class Gate internal constructor(internal val pathContains: String, internal val answerFirst: Boolean = false) {
        private val arrived = CountDownLatch(1)
        private val released = CountDownLatch(1)
        internal fun arrive() = arrived.countDown()
        internal fun awaitRelease() { released.await(30, TimeUnit.SECONDS) }
        fun awaitArrived(timeoutMs: Long = 5_000): Boolean = arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        fun release() = released.countDown()
    }

    private val gates = java.util.concurrent.CopyOnWriteArrayList<Gate>()

    /**
     * Holds every request whose "path?query" contains [pathContains] until [Gate.release]. Requests
     * that arrive after the release go straight through. With [answerFirst] the response is computed
     * from the server's state when the request ARRIVES and only delivered at the release: a reply that
     * was already in flight (and so can't see what happened while it was held).
     */
    fun hold(pathContains: String, answerFirst: Boolean = false): Gate = Gate(pathContains, answerFirst).also { gates += it }

    /** Lets every held request go (test teardown). */
    fun releaseAllGates() = gates.forEach { it.release() }

    private class Throttle(val pathContains: String, val remaining: AtomicInteger, val code: Int = 429)

    private val throttles = java.util.concurrent.CopyOnWriteArrayList<Throttle>()

    /** The next [times] requests whose "path?query" contains [pathContains] answer 429 with `Retry-After: 0`. */
    fun respond429(pathContains: String, times: Int) {
        throttles += Throttle(pathContains, AtomicInteger(times), 429)
    }

    /** The next [times] requests whose "path?query" contains [pathContains] answer [code] (a 500, a 401, ...). */
    fun respondWith(pathContains: String, code: Int, times: Int) {
        throttles += Throttle(pathContains, AtomicInteger(times), code)
    }

    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Replaces the answer to any `!parents` request (may throw an IOException). */
    var parentsOverride: ((Request) -> Response)? = null

    /** What a POST to `!unlock` answers. */
    var unlockCode: Int = 200

    /**
     * The session cookie (R-68). The app's OkHttp cookie jar is global per host, so any successful
     * `!unlock` makes every later request carry the cookie; the fake models that jar here instead of
     * parsing a Cookie header (an application interceptor runs before OkHttp adds it). The fake
     * answers the response with a Set-Cookie header too, as SmugMug does.
     */
    private val unlockedRoots = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** When true, Family's and School's `!children` answer 401 until `node/2sDN5x!unlock` succeeded. */
    var cookieGate = false

    /**
     * When true the session is judged from the request's real `Cookie` header (what the production
     * cookie jar sends over [LoopbackSmugMug]) instead of the interceptor-mode model above, so a new
     * process (an empty jar) is anonymous again even though the server still remembers the unlock.
     */
    var sessionFromCookieHeader = false

    /**
     * When true, `album/{key}!images` of a gallery that needs a session answers the locked shape
     * (200, no `AlbumImage`, `Total` 0) and `album/{key}` says `ResponseLevel: "Password"`, until the
     * session exists (design P7). Off by default: the Phase 3 scenarios serve those galleries freely.
     */
    var gateImages = false

    private fun sessionOk(req: Request, rootId: String = "2sDN5x"): Boolean {
        if (rootId !in unlockedRoots) return false
        return !sessionFromCookieHeader || req.header("Cookie")?.contains("SMSESS=") == true
    }

    /**
     * When true, a GET of any endpoint in `accepted-params.json` that the fake has no specific answer
     * for gets a 200 with a placeholder body (and the `Response.Uri` echo), so `ApiContractTest` can
     * check the parameters of every `SmugMugApi` method. Off by default: the Phase 3 scenarios keep
     * their 404 for what the fake does not model.
     */
    var genericAnswers = false

    /** Page-size caps of the live API (design P13). `imageCaps` overrides [imagesPageCap] per album. */
    var childrenPageCap = 200
    var albumsPageCap = 100
    var imagesPageCap = 500
    var searchPageCap = 100

    /**
     * `FfHCms` keeps the 100-per-page cap the Phase 3 scenarios were built on (150 images = two pages);
     * the live API honours 500 (P13), which is what every other gallery gets.
     */
    val imageCaps: MutableMap<String, Int> = mutableMapOf("FfHCms" to 100)

    /** How many results `image!search` has for any query (the live API reports at most 10,000). */
    var imageSearchTotal = 0

    /** `user!topkeywords` for the whole site, and for a node when `NodeURI=/api/v2/node/{id}` is sent. */
    var siteTopKeywords: List<String> = listOf("a", "b", "c", "d", "e")
    val nodeTopKeywords: MutableMap<String, List<String>> = mutableMapOf("P4BKB" to listOf("a", "b"))

    /** Requests (method + path) that went out while the session cookie existed. */
    val requestsWithSession = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** True once a `!unlock` for [rootId] succeeded (the jar holds its cookie). */
    fun hasSession(rootId: String = "2sDN5x") = rootId in unlockedRoots

    /** NodeIDs that `node!search` answers with (any query). */
    var searchResultIds: List<String> = emptyList()

    /** Every request in full (headers included), for assertions such as Cache-Control. */
    val requestLog = java.util.Collections.synchronizedList(mutableListOf<Request>())

    /**
     * Replaces the answer to a `node/{id}!children` request: return a Response, throw an IOException,
     * or return null to fall through to the normal answer.
     */
    var childrenOverride: ((nodeId: String, req: Request) -> Response?)? = null

    /** Cache-Control header values of the `!children` requests for [nodeId], in order. */
    fun childrenCacheControls(nodeId: String): List<String?> = synchronized(requestLog) {
        requestLog.filter { it.url.encodedPath.endsWith("node/$nodeId!children") }.map { it.header("Cache-Control") }
    }

    /**
     * One entry of the `user!albums` listing (V1/V6 shapes: `_verbosity=1` strings, no ParentNode,
     * `Uris.Folder` = the containing folder). [needsSession] galleries sit under the password folder
     * and are listed only once the session cookie exists (R-68), when [cookieGate] is on.
     */
    data class FakeAlbum(
        val albumKey: String,
        val nodeId: String,
        val name: String,
        val urlPath: String,
        val lastUpdated: String,
        val imagesLastUpdated: String?,
        val needsSession: Boolean = false,
        val security: String = "None"
    ) {
        val folderPath: String get() = urlPath.substringBeforeLast('/')
    }

    /** SmugMug's date shape, e.g. `2026-09-28T10:00:00+00:00`, relative to now (findings #9). */
    fun daysAgo(days: Long, extraSeconds: Long = 0): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(days).plusSeconds(extraSeconds)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

    /** What `user!albums` lists, in listing order. Default: fixture F's two galleries. */
    var albums: List<FakeAlbum> = listOf(
        FakeAlbum(
            "FfHCms", "LCdk7F", "New School Year", "/Family/School/2026-09-01--New-School-Year",
            lastUpdated = daysAgo(6), imagesLastUpdated = daysAgo(2), needsSession = true, security = "None"
        ),
        FakeAlbum(
            "N74KSK", "sXQz4G", "Public Gallery", "/Kentridge/Public",
            lastUpdated = daysAgo(40), imagesLastUpdated = daysAgo(40)
        )
    )

    /** What `user!albums` lists for site B (`siteb`). */
    var albumsB: List<FakeAlbum> = listOf(
        FakeAlbum(
            "jX9wQe", "Vt4Kp8", "Site B Gallery", "/Public/Site-B-Gallery",
            lastUpdated = daysAgo(3), imagesLastUpdated = daysAgo(3)
        )
    )

    private fun albumsOf(nickname: String) = if (nickname == SITE_B) albumsB else albums
    private fun hostOf(nickname: String) =
        if (nickname == SITE_B) "https://siteb.smugmug.com" else "https://gallery.idzifamily.com"

    /** How many images each album holds (default 12); pages are capped at 100 like the live API. */
    val imageCounts: MutableMap<String, Int> = mutableMapOf("FfHCms" to 150)

    /** ImageKeys of [albumKey], in listing order. */
    fun imageKeysOf(albumKey: String): List<String> =
        (1..(imageCounts[albumKey] ?: 12)).map { "${albumKey}i${it.toString().padStart(3, '0')}" }

    /** Replaces the answer to a `user!albums` page: return a Response, throw, or null to fall through. */
    var albumsOverride: ((start: Int, req: Request) -> Response?)? = null

    /** The `user!albums` requests, in order. */
    fun albumsRequests(): List<Request> = synchronized(requestLog) {
        requestLog.filter { it.url.encodedPath.endsWith("!albums") }
    }

    private fun albumsPage(req: Request, nickname: String): Response {
        val start = req.url.queryParameter("start")?.toIntOrNull() ?: 1
        albumsOverride?.invoke(start, req)?.let { return it }
        val count = (req.url.queryParameter("count")?.toIntOrNull() ?: 100).coerceIn(1, albumsPageCap)
        val visible = albumsOf(nickname).filter { !(cookieGate && it.needsSession && !sessionOk(req)) }
        val page = visible.drop(start - 1).take(count)
        val body = page.joinToString(",") {
            val ilu = it.imagesLastUpdated?.let { v -> ""","ImagesLastUpdated":"$v"""" } ?: ""
            """{"Uri":"/api/v2/album/${it.albumKey}","AlbumKey":"${it.albumKey}","NodeID":"${it.nodeId}","Name":"${it.name}",""" +
                """"UrlPath":"${it.urlPath}","WebUri":"${hostOf(nickname)}${it.urlPath}","SecurityType":"${it.security}",""" +
                """"ImageCount":12,"LastUpdated":"${it.lastUpdated}"$ilu,""" +
                """"Uris":{${urisJson(req, mapOf("Folder" to "/api/v2/folder/user/$nickname${it.folderPath}"))}}}"""
        }
        // Like the live API, NextPage drops _expand and _verbosity (design V3).
        val next = if (start - 1 + page.size < visible.size) ""","NextPage":"${nextPage(req, "user/$nickname!albums", start + page.size)}"""" else ""
        return json(
            req, 200,
            """{"Response":{"Album":[$body],"Pages":{"Start":$start,"Count":${page.size},"Total":${visible.size},"RequestedCount":$count$next}},"Code":200}"""
        )
    }

    private data class N(
        val id: String, val type: String, val name: String, val sec: String, val eff: String, val web: String,
        /** Has a highlight image: `Uris.HighlightImage`, and an expansion when `_expand` asks for it. */
        val hl: Boolean = false
    )

    private val root = N("4zqWw", "Folder", "Home", "None", "None", "https://gallery.idzifamily.com")
    private val family = N("2sDN5x", "Folder", "Family", "Password", "Password", "https://gallery.idzifamily.com/Family")
    private val school = N("P4BKB", "Folder", "School", "None", "Password", "https://gallery.idzifamily.com/Family/School")
    private val gallery = N(
        "LCdk7F", "Album", "New School Year", "None", "Password",
        "https://gallery.idzifamily.com/Family/School/2026-09-01--New-School-Year"
    )
    private val publicFolder = N("3BxbFF", "Folder", "Kentridge", "None", "None", "https://gallery.idzifamily.com/Kentridge")
    private val publicGallery = N("sXQz4G", "Album", "Public Gallery", "None", "None", "https://gallery.idzifamily.com/Kentridge/Public")

    private val secondRoot = N("Wq8Rz3", "Folder", "Work", "Password", "Password", "https://gallery.idzifamily.com/Work")

    private val rootB = N("Rb7Tq2", "Folder", "Home", "None", "None", "https://siteb.smugmug.com")
    private val publicFolderB = N("Hq2Lm9", "Folder", "Public", "None", "None", "https://siteb.smugmug.com/Public")
    private val galleryB = N("Vt4Kp8", "Album", "Site B Gallery", "None", "None", "https://siteb.smugmug.com/Public/Site-B-Gallery")

    private val childrenOf: MutableMap<String, MutableList<N>> = mutableMapOf(
        root.id to mutableListOf(family, publicFolder),
        family.id to mutableListOf(school),
        school.id to mutableListOf(gallery),
        publicFolder.id to mutableListOf(publicGallery),
        secondRoot.id to mutableListOf(),
        rootB.id to mutableListOf(publicFolderB),
        publicFolderB.id to mutableListOf(galleryB)
    )

    /**
     * Public folder `Kp7Wq2` with 150 gallery children under the root (design section 5): the 150th is
     * node `Vn2Lt7` / album `Vd5Qx9` (620 images, videos at #3, #510, #615). Every child has a highlight
     * image. It joins the root listing only after [listBigFolder], so the Phase 3 scenarios see the
     * root they were written for; it is reachable by id from the start.
     */
    private val bigFolder = N(BIG_FOLDER, "Folder", "Meets", "None", "None", "https://gallery.idzifamily.com/Meets", hl = true)

    /** Adds [count] real-shaped galleries to [parentId]'s children (ids `Nd0001`.., albums `Ak0001`..). */
    fun addGalleries(parentId: String, count: Int, idPrefix: String = "Nd", albumPrefix: String = "Ak") {
        val web = lineages.getValue(parentId).first().web
        synchronized(childrenOf) {
            val have = childrenOf.getValue(parentId).size
            for (i in have + 1..have + count) {
                val n = "%04d".format(i)
                addChild(parentId, N("$idPrefix$n", "Album", "Meet $n", "None", "None", "$web/Meet-$n", hl = true), "$albumPrefix$n")
            }
        }
    }

    private fun addChild(parentId: String, n: N, albumKey: String?) {
        childrenOf.getOrPut(parentId) { mutableListOf() }.add(n)
        lineages[n.id] = listOf(n) + lineages.getValue(parentId)
        if (albumKey != null) albumKeyToNodeId[albumKey] = n.id
    }

    /** Makes the 150-child folder `Kp7Wq2` show up in site A's root listing from now on. */
    fun listBigFolder() {
        synchronized(childrenOf) { childrenOf.getValue(root.id).add(bigFolder) }
    }

    /** Makes `Wq8Rz3` (a second password root) show up in site A's root listing from now on. */
    fun listSecondPasswordRoot() {
        synchronized(childrenOf) { childrenOf.getValue(root.id).add(secondRoot) }
    }

    /**
     * Adds a sub-folder to [parentId]'s `!children` listing from now on (a folder created on the
     * site after the app cached its parent). [path] is its UrlPath, e.g. `/Family/School/New`.
     */
    fun addFolder(parentId: String, nodeId: String, name: String, path: String) {
        childrenOf.getOrPut(parentId) { mutableListOf() }
            .add(N(nodeId, "Folder", name, "None", "Password", "https://gallery.idzifamily.com$path"))
        childrenOf.putIfAbsent(nodeId, mutableListOf())
    }

    /** Lineage of each node, self first. */
    private val lineages: MutableMap<String, List<N>> = mutableMapOf(
        root.id to listOf(root),
        family.id to listOf(family, root),
        school.id to listOf(school, family, root),
        gallery.id to listOf(gallery, school, family, root),
        publicFolder.id to listOf(publicFolder, root),
        publicGallery.id to listOf(publicGallery, publicFolder, root),
        secondRoot.id to listOf(secondRoot, root),
        rootB.id to listOf(rootB),
        publicFolderB.id to listOf(publicFolderB, rootB),
        galleryB.id to listOf(galleryB, publicFolderB, rootB),
        bigFolder.id to listOf(bigFolder, root)
    )

    private val albumKeyToNodeId: MutableMap<String, String> =
        mutableMapOf("FfHCms" to "LCdk7F", "N74KSK" to "sXQz4G", "jX9wQe" to "Vt4Kp8")

    init {
        childrenOf[bigFolder.id] = mutableListOf()
        addGalleries(bigFolder.id, 149)
        addChild(
            bigFolder.id,
            N(BIG_GALLERY_NODE, "Album", "Meet 0150", "None", "None", "https://gallery.idzifamily.com/Meets/Meet-0150", hl = true),
            BIG_GALLERY_ALBUM
        )
        imageCounts[BIG_GALLERY_ALBUM] = 620
    }

    /** Which images of an album are videos (1-based positions). */
    val videoPositions: MutableMap<String, Set<Int>> = mutableMapOf(BIG_GALLERY_ALBUM to setOf(3, 510, 615))

    companion object {
        const val SITE_A = "idzifamily"
        const val SITE_B = "siteb"
        const val BIG_FOLDER = "Kp7Wq2"
        const val BIG_GALLERY_NODE = "Vn2Lt7"
        const val BIG_GALLERY_ALBUM = "Vd5Qx9"
        const val SEARCH_WINDOW = 10_000
    }

    val interceptor = Interceptor { chain -> handle(chain.request()) }

    /**
     * [retrying] wraps the client in the app's real [com.smugview.app.data.api.RetryingCallFactory]
     * (5 tries on 429/5xx), so one logical request is several requests on the wire, as in production.
     */
    fun api(retrying: Boolean = false): SmugMugApi {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val builder = Retrofit.Builder()
            .baseUrl("https://api.smugmug.com/api/v2/")
            .addConverterFactory(GsonConverterFactory.create())
        if (retrying) builder.callFactory(com.smugview.app.data.api.RetryingCallFactory(client, initialDelayMs = 1))
        else builder.client(client)
        return builder.build().create(SmugMugApi::class.java)
    }

    /** Requests (method + path) that match [pathContains], in order. */
    fun requestsTo(pathContains: String): List<String> = synchronized(requests) {
        requests.filter { it.contains(pathContains) }
    }

    /** SmugMug's own header on every API answer (findings V11); the app's network interceptor rewrites it. */
    private fun json(req: Request, code: Int, body: String) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .header("Cache-Control", "private, no-store, no-cache, max-age=0")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()

    /**
     * `"Uris"` entries of [all] that `_filteruri` lists (all of them when it is absent): strings with
     * `_verbosity=1`, link objects without it, as the live API does (design P2, findings #22).
     */
    private fun urisJson(req: Request, all: Map<String, String>): String {
        val wanted = req.url.queryParameter("_filteruri")?.split(',')?.map { it.trim() }?.toSet()
        val kept = if (wanted == null) all else all.filterKeys { it in wanted }
        val strings = req.url.queryParameter("_verbosity") == "1"
        return kept.entries.joinToString(",") { (k, v) ->
            if (strings) """"$k":"$v"""" else """"$k":{"Uri":"$v","Locator":"$k","LocatorType":"Object"}"""
        }
    }

    /** SmugMug's `NextPage`: the echo of the accepted parameters, without `start`, plus the next `start`. */
    private fun nextPage(req: Request, path: String, nextStart: Int): String {
        val echo = AcceptedParams.echoQuery(req.url, path).split('&').filter { it.isNotEmpty() && !it.startsWith("start=") }
        return "/api/v2/$path?" + (echo + "start=$nextStart").joinToString("&")
    }

    private fun expands(req: Request, name: String) =
        req.url.queryParameter("_expand")?.split(',')?.any { it.trim() == name } == true

    @Throws(IOException::class)
    internal fun handle(req: Request): Response {
        val path = req.url.encodedPath.removePrefix("/api/v2/")
        requests += "${req.method} $path"
        requestLog += req
        if (unlockedRoots.isNotEmpty()) requestsWithSession += "${req.method} $path"
        val target = "$path?${req.url.query.orEmpty()}"
        val gate = gates.firstOrNull { target.contains(it.pathContains) }
        if (gate != null && gate.answerFirst) {
            val early = route(req, path, target)
            gate.arrive()
            try { gate.awaitRelease() } catch (e: InterruptedException) { throw IOException("held request interrupted", e) }
            return early
        }
        gate?.let {
            it.arrive()
            try { it.awaitRelease() } catch (e: InterruptedException) { throw IOException("held request interrupted", e) }
        }
        return route(req, path, target)
    }

    /** [routeRaw] plus the `Response.Uri` echo of a 200 GET (accepted parameters only, design P1). */
    @Throws(IOException::class)
    private fun route(req: Request, path: String, target: String): Response {
        val resp = routeRaw(req, path, target)
        if (req.method != "GET" || resp.code != 200) return resp
        val body = resp.body?.string() ?: return resp
        val echo = AcceptedParams.echoQuery(req.url, path)
        val uri = "/api/v2/$path" + if (echo.isEmpty()) "" else "?$echo"
        val out = if (body.startsWith("""{"Response":{""") && !body.startsWith("""{"Response":{"Uri":"""))
            """{"Response":{"Uri":"$uri",""" + body.removePrefix("""{"Response":{""") else body
        return resp.newBuilder().body(out.toResponseBody(resp.body?.contentType() ?: "application/json".toMediaTypeOrNull())).build()
    }

    @Throws(IOException::class)
    private fun routeRaw(req: Request, path: String, target: String): Response {
        throttles.firstOrNull { target.contains(it.pathContains) && it.remaining.get() > 0 }?.let {
            if (it.remaining.getAndDecrement() > 0) {
                if (it.code != 429) return json(req, it.code, """{"Code":${it.code},"Message":"Injected"}""")
                return json(req, 429, """{"Code":429,"Message":"Too Many Requests"}""")
                    .newBuilder().header("Retry-After", "0").build()
            }
        }
        if (path.startsWith("node/") && path.endsWith("!children")) {
            val id = path.removePrefix("node/").removeSuffix("!children")
            childrenOverride?.invoke(id, req)?.let { return it }
            if (cookieGate && (id == family.id || id == school.id) && !sessionOk(req)) {
                return json(req, 401, """{"Code":401,"Message":"Unauthorized"}""")
            }
            if (cookieGate && id == secondRoot.id && !sessionOk(req, secondRoot.id)) {
                return json(req, 401, """{"Code":401,"Message":"Unauthorized"}""")
            }
            val kids = synchronized(childrenOf) { childrenOf[id]?.toList() } ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            val start = req.url.queryParameter("start")?.toIntOrNull() ?: 1
            val count = (req.url.queryParameter("count")?.toIntOrNull() ?: 100).coerceIn(1, childrenPageCap)
            val page = kids.drop(start - 1).take(count)
            val expansions = mutableListOf<String>()
            val nodes = page.joinToString(",") {
                val all = linkedMapOf<String, String>()
                if (it.type == "Folder") all["ChildNodes"] = "/api/v2/node/${it.id}!children"
                else all["Album"] = "/api/v2/album/${albumKeyToNodeId.entries.first { e -> e.value == it.id }.key}"
                if (it.hl) {
                    val hl = "/api/v2/highlight/node/${it.id}?_shorturis="
                    all["HighlightImage"] = hl
                    if (expands(req, "HighlightImage") && req.url.queryParameter("_filteruri")?.split(',')?.contains("HighlightImage") != false) {
                        expansions += """"$hl":{"Uri":"$hl","Locator":"Image","LocatorType":"Object","Image":{"ThumbnailUrl":"https://photos.smugmug.com/photos/hl-${it.id}/0/Th/hl-${it.id}-Th.jpg"}}"""
                    }
                }
                """{"Uri":"/api/v2/node/${it.id}","NodeID":"${it.id}","Type":"${it.type}","Name":"${it.name}",""" +
                    """"SecurityType":"${it.sec}","WebUri":"${it.web}","Uris":{${urisJson(req, all)}}}"""
            }
            val next = if (start - 1 + page.size < kids.size) ""","NextPage":"${nextPage(req, "node/$id!children", start + page.size)}"""" else ""
            val exp = if (expansions.isEmpty()) "" else ""","Expansions":{${expansions.joinToString(",")}}"""
            return json(
                req, 200,
                """{"Response":{"Node":[$nodes],"Pages":{"Start":$start,"Count":${page.size},"Total":${kids.size},"RequestedCount":$count$next}},"Code":200$exp}"""
            )
        }
        if (req.method == "POST" && path.endsWith("!unlock")) {
            val resp = json(req, unlockCode, "{}")
            if (unlockCode in 200..299) {
                unlockedRoots += path.substringAfter('/').removeSuffix("!unlock")
                // The names and attributes the live `!unlock` sets (findings, E1): `shm` (31 days) and the
                // session cookie `SMSESS`; the values are fakes, and neither rotates on later GETs.
                return resp.newBuilder()
                    .addHeader("Set-Cookie", "shm=fake-shm; Domain=.smugmug.com; Path=/; Max-Age=2678400; Secure; HttpOnly; SameSite=None")
                    .addHeader("Set-Cookie", "SMSESS=fake-session; Domain=.smugmug.com; Path=/; Secure; HttpOnly; SameSite=None")
                    .build()
            }
            return resp
        }
        if (req.method == "GET" && path.startsWith("user/") && path.endsWith("!albums")) {
            return albumsPage(req, path.removePrefix("user/").removeSuffix("!albums"))
        }
        if (req.method == "GET" && path.startsWith("user/") && !path.contains("!")) {
            val nick = path.removePrefix("user/")
            val rootId = if (nick == SITE_B) rootB.id else root.id
            return json(
                req, 200,
                """{"Response":{"User":{"NickName":"$nick","Name":"$nick","WebUri":"${hostOf(nick)}",""" +
                    """"Uris":{"Node":"/api/v2/node/$rootId"}}},"Code":200}"""
            )
        }
        if (req.method == "GET" && path.startsWith("album/") && path.endsWith("!images")) {
            val key = path.removePrefix("album/").removeSuffix("!images")
            val nodeId = albumKeyToNodeId[key] ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            val start = req.url.queryParameter("start")?.toIntOrNull() ?: 1
            val count = (req.url.queryParameter("count")?.toIntOrNull() ?: 100).coerceIn(1, imageCaps[key] ?: imagesPageCap)
            if (gateImages && isLocked(req, key)) {
                // Design P7: 200, no AlbumImage, Total 0.
                return json(req, 200, """{"Response":{"Pages":{"Total":0,"Start":$start,"Count":0,"RequestedCount":$count}},"Code":200}""")
            }
            val web = lineages.getValue(nodeId).first().web
            val keys = imageKeysOf(key)
            val page = keys.drop(start - 1).take(count)
            val expansions = mutableListOf<String>()
            val images = page.mapIndexed { i, ik ->
                val video = (start + i) in (videoPositions[key] ?: emptySet())
                val all = linkedMapOf<String, String>()
                if (video) {
                    val lv = "/api/v2/image/$ik-0!largestvideo"
                    all["LargestVideo"] = lv
                    if (expands(req, "LargestVideo")) {
                        expansions += """"$lv":{"Uri":"$lv","Locator":"LargestVideo","LocatorType":"Object","LargestVideo":{"Url":"https://photos.smugmug.com/photos/$ik/0/1920/$ik-1920.mp4"}}"""
                    }
                }
                all["Album"] = "/api/v2/album/$key"
                all["ImageSizeDetails"] = "/api/v2/image/$ik-0!sizedetails"
                """{"Uri":"/api/v2/album/$key/image/$ik-0","ImageKey":"$ik","Title":"","FileName":"$ik.${if (video) "mp4" else "jpg"}","Format":"${if (video) "MP4" else "JPG"}",""" +
                    """"ThumbnailUrl":"https://photos.smugmug.com/photos/$ik/0/Th/$ik-Th.jpg","WebUri":"$web/i-$ik",""" +
                    """"OriginalWidth":4000,"OriginalHeight":3000,"OriginalSize":3145728,"Date":"${daysAgo(10)}",""" +
                    """"Uris":{${urisJson(req, all)}}}"""
            }.joinToString(",")
            val next = if (start - 1 + page.size < keys.size) ""","NextPage":"${nextPage(req, "album/$key!images", start + page.size)}"""" else ""
            val exp = if (expansions.isEmpty()) "" else ""","Expansions":{${expansions.joinToString(",")}}"""
            return json(
                req, 200,
                """{"Response":{"AlbumImage":[$images],"Pages":{"Start":$start,"Count":${page.size},"Total":${keys.size},"RequestedCount":$count$next}},"Code":200$exp}"""
            )
        }
        if (path.startsWith("node/") && path.endsWith("!parents")) {
            parentsOverride?.let { return it(req) }
            val id = path.removePrefix("node/").removeSuffix("!parents")
            val chain = lineages[id] ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            val nodes = chain.joinToString(",") {
                """{"Uri":"/api/v2/node/${it.id}","NodeID":"${it.id}","Type":"${it.type}","Name":"${it.name}",""" +
                    """"SecurityType":"${it.sec}","EffectiveSecurityType":"${it.eff}"}"""
            }
            return json(req, 200, """{"Response":{"Node":[$nodes]},"Code":200,"Message":"Ok"}""")
        }
        if (path.startsWith("album/") && !path.contains("!")) {
            val key = path.removePrefix("album/")
            val nodeId = albumKeyToNodeId[key] ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            // A gallery that is in `albums` also reports its dates (the 2-10 getAlbum filter asks for them).
            val known = (albums + albumsB).firstOrNull { it.albumKey == key }
            val dates = known?.let {
                val ilu = it.imagesLastUpdated?.let { v -> ""","ImagesLastUpdated":"$v"""" } ?: ""
                ""","LastUpdated":"${it.lastUpdated}"$ilu"""
            } ?: ""
            // Real galleries answer their own name and WebUri; the gallery screen shows both (R-18).
            val name = known?.name ?: "g"
            val webUri = known?.let { ""","WebUri":"${hostOf(if (it in albumsB) SITE_B else "")}${it.urlPath}"""" } ?: ""
            val level = if (gateImages && isLocked(req, key)) "Password" else "Public"
            return json(
                req, 200,
                """{"Response":{"Album":{"Uri":"/api/v2/album/$key","AlbumKey":"$key","NodeID":"$nodeId","Name":"$name"$webUri$dates,"ResponseLevel":"$level"}},"Code":200}"""
            )
        }
        if (path == "node!search") {
            val nodes = searchResultIds.mapNotNull { lineages[it]?.first() }.joinToString(",") { n ->
                val album = albumKeyToNodeId.entries.firstOrNull { it.value == n.id }?.key
                    ?.let { ""","Album":"/api/v2/album/$it"""" } ?: ""
                """{"Uri":"/api/v2/node/${n.id}","NodeID":"${n.id}","Type":"${n.type}","Name":"${n.name}",""" +
                    """"SecurityType":"${n.sec}","Uris":{"ParentNode":"/api/v2/node/${n.id}!parent"$album}}"""
            }
            return json(req, 200, """{"Response":{"Node":[$nodes]},"Code":200}""")
        }
        if (path.startsWith("node/") && !path.contains("!")) {
            val id = path.removePrefix("node/")
            val n = lineages[id]?.first() ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            return json(
                req, 200,
                """{"Response":{"Node":{"Uri":"/api/v2/node/${n.id}","NodeID":"${n.id}","Type":"${n.type}","Name":"${n.name}",""" +
                    """"SecurityType":"${n.sec}","Uris":{"ParentNode":"/api/v2/node/${n.id}!parent"}}},"Code":200}"""
            )
        }
        if (req.method == "GET" && path == "image!search") return imageSearch(req)
        if (req.method == "GET" && path.startsWith("user/") && path.endsWith("!topkeywords")) return topKeywords(req, path)
        if (req.method == "GET" && path.startsWith("image/")) return imageRoute(req, path)
        if (genericAnswers && req.method == "GET" && AcceptedParams.templateOf(path) in AcceptedParams.templates) {
            return json(req, 200, """{"Response":{"Generic":true},"Code":200}""")
        }
        return json(req, 404, """{"Code":404,"Message":"unhandled in fake"}""")
    }

    /** A gallery that needs the session and has none (only meaningful with [gateImages]). */
    private fun isLocked(req: Request, albumKey: String): Boolean =
        (albums + albumsB).firstOrNull { it.albumKey == albumKey }?.needsSession == true && !sessionOk(req)

    /**
     * `image!search`: [imageSearchTotal] results for any query, reported as at most 10,000 (design P6);
     * pages are capped at [searchPageCap]; a window that ends past 10,000 answers the live API's empty
     * `500 text/html` (`start=9902` with 100 per page does).
     */
    private fun imageSearch(req: Request): Response {
        val start = req.url.queryParameter("start")?.toIntOrNull() ?: 1
        val count = (req.url.queryParameter("count")?.toIntOrNull() ?: 100).coerceIn(1, searchPageCap)
        if (start + count - 1 > SEARCH_WINDOW) {
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(500).message("Internal Server Error")
                .body("".toResponseBody("text/html".toMediaTypeOrNull())).build()
        }
        val total = imageSearchTotal.coerceAtMost(SEARCH_WINDOW)
        val keys = (start..minOf(start + count - 1, total)).map { "s" + it.toString().padStart(5, '0') }
        val images = keys.joinToString(",") { ik ->
            """{"Uri":"/api/v2/image/$ik-0","ImageKey":"$ik","Title":"","Caption":"","FileName":"$ik.jpg","Format":"JPG",""" +
                """"ThumbnailUrl":"https://photos.smugmug.com/photos/$ik/0/Th/$ik-Th.jpg","Date":"${daysAgo(10)}",""" +
                """"KeywordArray":["kentridge"],"Keywords":"kentridge","Uris":{${urisJson(req, mapOf("ImageSizeDetails" to "/api/v2/image/$ik-0!sizedetails"))}}}"""
        }
        val next = if (start - 1 + keys.size < total) ""","NextPage":"${nextPage(req, "image!search", start + keys.size)}"""" else ""
        return json(
            req, 200,
            """{"Response":{"Image":[$images],"Pages":{"Total":$total,"Start":$start,"Count":${keys.size},"RequestedCount":$count$next}},"Code":200}"""
        )
    }

    /** `user/{n}!topkeywords`: scoped by `NodeURI=/api/v2/node/{id}` only; `NodeID` is ignored like the live API. */
    private fun topKeywords(req: Request, path: String): Response {
        val node = req.url.queryParameter("NodeURI")?.removePrefix("/api/v2/node/")
        val all = if (node != null) nodeTopKeywords[node] ?: emptyList() else siteTopKeywords
        val n = req.url.queryParameter("NumKeywords")?.toIntOrNull() ?: all.size
        val list = all.take(n).joinToString(",") { """"$it"""" }
        return json(
            req, 200,
            """{"Response":{"UserTopKeywords":{"TopKeywords":[$list],"Uri":"/api/v2/$path"}},"Code":200}"""
        )
    }

    /**
     * `image/{key}` 301s to `image/{key}-0` with the same query and `no-store` (design P5), and so does
     * `image/{key}!metadata`; `-0` answers. Keys are the fake's own: `{albumKey}i###` and `s#####`.
     */
    private fun imageRoute(req: Request, path: String): Response {
        val rest = path.removePrefix("image/")
        val key = rest.substringBefore('!').removeSuffix("-0")
        val suffix = if (rest.contains('!')) "!" + rest.substringAfter('!') else ""
        val known = Regex("^s\\d{5}$").matches(key) || albumKeyToNodeId.keys.any { key.startsWith(it + "i") }
        if (!known) return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
        if (!rest.substringBefore('!').endsWith("-0")) {
            val query = req.url.query?.let { "?$it" }.orEmpty()
            return json(req, 301, "").newBuilder().header("Location", "/api/v2/image/$key-0$suffix$query").build()
        }
        return if (suffix == "!metadata") json(
            req, 200,
            """{"Response":{"ImageMetadata":{"Make":"Canon","Model":"Canon EOS R6","Aperture":"f/2.8","ISO":400}},"Code":200}"""
        ) else json(
            req, 200,
            """{"Response":{"Image":{"Uri":"/api/v2/image/$key-0","ImageKey":"$key","Title":"","FileName":"$key.jpg","Format":"JPG",""" +
                """"ThumbnailUrl":"https://photos.smugmug.com/photos/$key/0/Th/$key-Th.jpg","OriginalWidth":4000,"OriginalHeight":3000,""" +
                """"Uris":{${urisJson(req, mapOf("ImageSizeDetails" to "/api/v2/image/$key-0!sizedetails"))}}}},"Code":200}"""
        )
    }
}
