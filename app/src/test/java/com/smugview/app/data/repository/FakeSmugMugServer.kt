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
 */
class FakeSmugMugServer {
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

    /** Replaces the answer to a `user!albums` page: return a Response, throw, or null to fall through. */
    var albumsOverride: ((start: Int, req: Request) -> Response?)? = null

    /** The `user!albums` requests, in order. */
    fun albumsRequests(): List<Request> = synchronized(requestLog) {
        requestLog.filter { it.url.encodedPath.endsWith("!albums") }
    }

    private fun albumsPage(req: Request, nickname: String): Response {
        val start = req.url.queryParameter("start")?.toIntOrNull() ?: 1
        albumsOverride?.invoke(start, req)?.let { return it }
        val count = (req.url.queryParameter("count")?.toIntOrNull() ?: 100).coerceAtMost(100)
        val visible = albums.filter { !(cookieGate && it.needsSession && family.id !in unlockedRoots) }
        val page = visible.drop(start - 1).take(count)
        val body = page.joinToString(",") {
            val ilu = it.imagesLastUpdated?.let { v -> ""","ImagesLastUpdated":"$v"""" } ?: ""
            """{"Uri":"/api/v2/album/${it.albumKey}","AlbumKey":"${it.albumKey}","NodeID":"${it.nodeId}","Name":"${it.name}",""" +
                """"UrlPath":"${it.urlPath}","WebUri":"https://gallery.idzifamily.com${it.urlPath}","SecurityType":"${it.security}",""" +
                """"ImageCount":12,"LastUpdated":"${it.lastUpdated}"$ilu,""" +
                """"Uris":{"Folder":"/api/v2/folder/user/$nickname${it.folderPath}"}}"""
        }
        // Like the live API, NextPage drops _expand and _verbosity (design V3).
        val next = if (start - 1 + page.size < visible.size)
            ""","NextPage":"/api/v2/user/$nickname!albums?count=$count&start=${start + count}"""" else ""
        return json(
            req, 200,
            """{"Response":{"Album":[$body],"Pages":{"Start":$start,"Count":${page.size},"Total":${visible.size}$next}},"Code":200}"""
        )
    }

    private data class N(
        val id: String, val type: String, val name: String, val sec: String, val eff: String, val web: String
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

    private val childrenOf: Map<String, List<N>> = mapOf(
        root.id to listOf(family, publicFolder),
        family.id to listOf(school),
        school.id to listOf(gallery),
        publicFolder.id to listOf(publicGallery)
    )

    /** Lineage of each node, self first. */
    private val lineages: Map<String, List<N>> = mapOf(
        root.id to listOf(root),
        family.id to listOf(family, root),
        school.id to listOf(school, family, root),
        gallery.id to listOf(gallery, school, family, root),
        publicFolder.id to listOf(publicFolder, root),
        publicGallery.id to listOf(publicGallery, publicFolder, root)
    )

    private val albumKeyToNodeId = mapOf("FfHCms" to "LCdk7F", "N74KSK" to "sXQz4G")

    val interceptor = Interceptor { chain -> handle(chain.request()) }

    fun api(): SmugMugApi = Retrofit.Builder()
        .baseUrl("https://api.smugmug.com/api/v2/")
        .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SmugMugApi::class.java)

    /** Requests (method + path) that match [pathContains], in order. */
    fun requestsTo(pathContains: String): List<String> = synchronized(requests) {
        requests.filter { it.contains(pathContains) }
    }

    private fun json(req: Request, code: Int, body: String) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()

    @Throws(IOException::class)
    private fun handle(req: Request): Response {
        val path = req.url.encodedPath.removePrefix("/api/v2/")
        requests += "${req.method} $path"
        requestLog += req
        if (unlockedRoots.isNotEmpty()) requestsWithSession += "${req.method} $path"
        if (path.startsWith("node/") && path.endsWith("!children")) {
            val id = path.removePrefix("node/").removeSuffix("!children")
            childrenOverride?.invoke(id, req)?.let { return it }
            if (cookieGate && (id == family.id || id == school.id) && family.id !in unlockedRoots) {
                return json(req, 401, """{"Code":401,"Message":"Unauthorized"}""")
            }
            val kids = childrenOf[id] ?: return json(req, 404, """{"Code":404,"Message":"Not Found"}""")
            val nodes = kids.joinToString(",") {
                val uris = if (it.type == "Folder") """"ChildNodes":"/api/v2/node/${it.id}!children""""
                else """"Album":"/api/v2/album/${albumKeyToNodeId.entries.first { e -> e.value == it.id }.key}""""
                """{"Uri":"/api/v2/node/${it.id}","NodeID":"${it.id}","Type":"${it.type}","Name":"${it.name}",""" +
                    """"SecurityType":"${it.sec}","WebUri":"${it.web}","Uris":{$uris}}"""
            }
            return json(req, 200, """{"Response":{"Node":[$nodes]},"Code":200}""")
        }
        if (req.method == "POST" && path.endsWith("!unlock")) {
            val resp = json(req, unlockCode, "{}")
            if (unlockCode in 200..299) {
                unlockedRoots += path.substringAfter('/').removeSuffix("!unlock")
                return resp.newBuilder().addHeader("Set-Cookie", "SMSESS=fake-session; Path=/; HttpOnly").build()
            }
            return resp
        }
        if (req.method == "GET" && path.startsWith("user/") && path.endsWith("!albums")) {
            return albumsPage(req, path.removePrefix("user/").removeSuffix("!albums"))
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
            return json(
                req, 200,
                """{"Response":{"Album":{"Uri":"/api/v2/album/$key","AlbumKey":"$key","NodeID":"$nodeId","Name":"g"}},"Code":200}"""
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
        return json(req, 404, """{"Code":404,"Message":"unhandled in fake"}""")
    }
}
