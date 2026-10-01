package com.smugview.app.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl

/**
 * The GET parameters each SmugMug endpoint accepts, captured from live `OPTIONS` requests and
 * committed as `api-contract/accepted-params.json` (phase 4 design 3.1, evidence P1). SmugMug
 * answers `Response.Uri` with only the parameters it accepted, so this list is the oracle for what
 * the fake echoes and for what `ApiContractTest` allows the app to send.
 */
object AcceptedParams {
    private class File(
        @SerializedName("meta") val meta: List<String>,
        @SerializedName("endpoints") val endpoints: Map<String, List<String>>
    )

    private val file: File by lazy {
        val stream = AcceptedParams::class.java.classLoader!!.getResourceAsStream("api-contract/accepted-params.json")
            ?: error("api-contract/accepted-params.json is not on the test classpath")
        stream.reader().use { Gson().fromJson(it, File::class.java) }
    }

    /** Names every endpoint accepts: APIKey, _expand, _filter, _filteruri, _shorturis, _verbosity, count, start. */
    val meta: Set<String> get() = file.meta.toSet()

    /** The templates of the file, e.g. `user/{n}!albums`. */
    val templates: Set<String> get() = file.endpoints.keys

    /** SmugMug never echoes these, although it accepts them. */
    val notEchoed: Set<String> = setOf("APIKey", "_expand", "_verbosity")

    private val pathVar = mapOf("user" to "{n}", "node" to "{id}", "album" to "{k}", "image" to "{k}")

    /** `user/idzifamily!albums` -> `user/{n}!albums`; `node!search` stays. A path with no template maps to itself. */
    fun templateOf(path: String): String {
        val p = path.removePrefix("/api/v2/").substringBefore('?')
        val m = Regex("^(user|node|album|image)/([^!/]+)(!.*)?$").find(p) ?: return p
        return "${m.groupValues[1]}/${pathVar.getValue(m.groupValues[1])}${m.groupValues[3]}"
    }

    /** The endpoint-specific names for [path]'s template (empty when it has none, or is unknown). */
    fun endpointParams(path: String): Set<String> = file.endpoints[templateOf(path)]?.toSet() ?: emptySet()

    /** Meta names plus the endpoint's own: everything that is not dropped from the echo. */
    fun accepted(path: String): Set<String> = meta + endpointParams(path)

    /**
     * What SmugMug puts after `?` in `Response.Uri` (and in `NextPage`, minus `start`): the accepted
     * parameters in the order they were sent, without [notEchoed]. `Password`, `NodeID`, `Text` on an
     * endpoint that does not accept it, and every other unknown name are dropped.
     */
    fun echoQuery(url: HttpUrl, path: String = url.encodedPath): String {
        val ok = accepted(path) - notEchoed
        val out = mutableListOf<String>()
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            if (name in ok) out += "${encode(name)}=${encode(url.queryParameterValue(i).orEmpty())}"
        }
        return out.joinToString("&")
    }

    private fun encode(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}
