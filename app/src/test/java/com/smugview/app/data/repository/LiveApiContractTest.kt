package com.smugview.app.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 4 step 4-0 (design 5.4): the facts the fake and `accepted-params.json` rest on, checked against
 * the LIVE API, read-only (GET and OPTIONS on public paths, never an unlock, never a write).
 *
 * It runs only when `smugmug.api.key` is in `local.properties` AND `SMUGVIEW_LIVE_API=1` is set, so a
 * normal `testDebugUnitTest` never touches the network. The key is read here and never printed: assertion
 * messages carry paths and names, not URLs with the key.
 */
class LiveApiContractTest {
    private val client = OkHttpClient()
    private lateinit var key: String

    @Before fun gate() {
        assumeTrue("set SMUGVIEW_LIVE_API=1 to run the live contract test", System.getenv("SMUGVIEW_LIVE_API") == "1")
        key = LocalProps.get("smugmug.api.key") ?: ""
        assumeTrue("no smugmug.api.key in local.properties", key.isNotEmpty())
    }

    private fun call(method: String, path: String): Pair<Int, JsonObject?> {
        val sep = if (path.contains('?')) "&" else "?"
        val req = Request.Builder().url("https://api.smugmug.com/api/v2/$path${sep}APIKey=$key")
            .header("Accept", "application/json").method(method, null).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return r.code to runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
    }

    private fun response(path: String): JsonObject = call("GET", path).second!!.getAsJsonObject("Response")
    private fun echo(path: String) = response(path).get("Uri").asString.substringAfter('?', "")

    /** Public sample paths per template (nickname `idzifamily`, a public folder and gallery). */
    private val samples = mapOf(
        "user/{n}!albums" to "user/idzifamily!albums",
        "node/{id}!children" to "node/3BxbFF!children",
        "album/{k}!images" to "album/N74KSK!images",
        "image!search" to "image!search",
        "node/{id}" to "node/3BxbFF",
        "album/{k}" to "album/N74KSK",
        "user/{n}" to "user/idzifamily",
        "user/{n}!topkeywords" to "user/idzifamily!topkeywords"
    )

    @Test fun options_lists_exactly_the_endpoint_params_in_accepted_params_json() {
        val problems = mutableListOf<String>()
        for ((template, path) in samples) {
            val (code, body) = call("OPTIONS", path)
            if (code != 200 || body == null) { problems += "$template: OPTIONS answered $code"; continue }
            // `Parameters` is an empty array (not an object) or has no GET for an endpoint with no own params.
            val params = body.getAsJsonObject("Options").get("Parameters")
            val live = (if (params != null && params.isJsonObject) params.asJsonObject.getAsJsonArray("GET") else null)
                ?.map { it.asJsonObject.get("Name").asString }?.toSet() ?: emptySet()
            val mine = AcceptedParams.endpointParams(path)
            if (live != mine) problems += "$template: live=${live.sorted()} file=${mine.sorted()}"
        }
        assertEquals("OPTIONS disagrees with accepted-params.json: $problems", emptyList<String>(), problems)
    }

    @Test fun the_uri_echo_drops_password_apikey_expand_and_verbosity() {
        val q = echo("node/3BxbFF!children?count=2&Password=notapassword&_expand=HighlightImage&_verbosity=1")
        assertFalse("echo kept Password", q.contains("Password"))
        assertFalse("echo kept _expand", q.contains("_expand"))
        assertFalse("echo kept _verbosity", q.contains("_verbosity"))
        assertFalse("echo kept APIKey", q.contains("APIKey"))
        assertTrue("echo lost count: $q", q.contains("count=2"))
    }

    @Test fun next_page_drops_expand_and_verbosity_and_adds_start() {
        val pages = response("node/3BxbFF!children?count=1&_expand=HighlightImage&_verbosity=1").getAsJsonObject("Pages")
        val next = pages.get("NextPage")?.asString
        assertTrue("the public folder must have more than one child for this check", next != null)
        assertFalse(next!!.contains("_expand"))
        assertFalse(next.contains("_verbosity"))
        assertTrue(next.contains("start=2"))
    }

    @Test fun a_param_the_endpoint_does_not_accept_is_not_echoed() {
        assertFalse(echo("user/idzifamily!topkeywords?NodeID=3BxbFF").contains("NodeID"))
    }
}
