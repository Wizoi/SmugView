package com.smugview.app.diag

import com.smugview.app.data.api.CallOutcome
import okhttp3.HttpUrl
import okhttp3.Response

enum class CacheSource { NETWORK, CACHE, CONDITIONAL, SYNTHETIC_504, NONE }

data class CategoryStats(
    val calls: Int = 0,
    val status2xx: Int = 0,
    val status3xx: Int = 0,
    val status4xx: Int = 0,
    val status5xx: Int = 0,
    val cache: Int = 0,
    val conditional: Int = 0,
    val synthetic504: Int = 0,
    val retriedCalls: Int = 0,
    val totalRetries: Int = 0,
    val ioErrors: Int = 0,
    val canceled: Int = 0,
    val maxMs: Long = 0,
    val meanMs: Long = 0
)

data class HttpStats(val api: CategoryStats = CategoryStats(), val img: CategoryStats = CategoryStats())

private const val API_HOST = "api.smugmug.com"
private const val SLOW_MS = 3000L
private const val MAX_VALUE_CHARS = 40
private val ALLOWED_PARAMS = setOf("start", "count", "Order", "SortMethod", "SortDirection", "_verbosity", "_expand")
private val CDN_TIER = Regex("""^(X[2-5L]|X[2-5]L|[SMLO]|XL|Th|Ti)$""")

/** How a settled response was served; see design §2.3. */
fun classify(response: Response): CacheSource {
    val network = response.networkResponse
    val cached = response.cacheResponse
    return when {
        network == null && cached != null -> CacheSource.CACHE
        network != null && cached != null -> CacheSource.CONDITIONAL
        network != null -> CacheSource.NETWORK
        response.code == 504 -> CacheSource.SYNTHETIC_504
        else -> CacheSource.NONE
    }
}

/**
 * A log-safe description of a request URL (design §3.3): no titles, no CDN paths, no query values
 * outside a small allow-list. Other query parameters appear by name only, e.g. `+APIKey,+Password`.
 */
fun describeUrl(url: HttpUrl): String {
    val isApi = url.host == API_HOST
    val segments = url.pathSegments.filter { it.isNotEmpty() }
    val head: String
    if (isApi) {
        val s = if (segments.take(2) == listOf("api", "v2")) segments.drop(2) else segments
        head = "/" + when {
            // /folder/user/<nick>/<Titles/...>: the title path names family members.
            s.size > 3 && s[0] == "folder" && s[1] == "user" -> "folder/user/${s[2]}/<path>"
            else -> s.joinToString("/")
        }
    } else {
        val tier = segments.getOrNull(segments.size - 2)?.takeIf { segments.size >= 2 && CDN_TIER.matches(it) }
        head = url.host + if (segments.isEmpty()) "" else " <path:${segments.size} segs>" + (tier?.let { "/$it" } ?: "")
    }
    val shown = mutableListOf<String>()
    val hidden = mutableListOf<String>()
    for (i in 0 until url.querySize) {
        val name = url.queryParameterName(i)
        if (name in ALLOWED_PARAMS) {
            shown.add("$name=${(url.queryParameterValue(i) ?: "").take(MAX_VALUE_CHARS)}")
        } else if ("+$name" !in hidden) {
            hidden.add("+$name")
        }
    }
    if (shown.isEmpty() && hidden.isEmpty()) return head
    val query = shown.joinToString("&") + (if (hidden.isNotEmpty()) (if (shown.isEmpty()) "" else "&") + hidden.joinToString(",") else "")
    return "$head?$query"
}

/** Turns each settled request ([CallOutcome]) into one diagnostics line plus in-memory counters. */
class HttpTelemetry(private val log: DiagLog) {
    private class Acc {
        var calls = 0; var s2 = 0; var s3 = 0; var s4 = 0; var s5 = 0
        var cache = 0; var conditional = 0; var synthetic = 0
        var retriedCalls = 0; var totalRetries = 0; var ioErrors = 0; var canceled = 0
        var maxMs = 0L; var totalMs = 0L
        fun toStats() = CategoryStats(
            calls, s2, s3, s4, s5, cache, conditional, synthetic, retriedCalls, totalRetries,
            ioErrors, canceled, maxMs, if (calls == 0) 0 else totalMs / calls
        )
    }

    private val lock = Any()
    private val api = Acc()
    private val img = Acc()

    fun stats(): HttpStats = synchronized(lock) { HttpStats(api.toStats(), img.toStats()) }

    fun record(o: CallOutcome) {
        try {
            val isApi = o.request.url.host == API_HOST
            val response = o.response
            val source = if (response != null) classify(response) else CacheSource.NONE
            count(if (isApi) api else img, o, source)
            if (!isApi && !worthLogging(o, source)) return
            log.log(level(o), "http", line(o, source))
        } catch (_: Throwable) {
            // Telemetry must never affect the request.
        }
    }

    private fun count(a: Acc, o: CallOutcome, source: CacheSource) = synchronized(lock) {
        a.calls++
        val code = o.response?.code
        when {
            o.canceled -> a.canceled++
            o.error != null -> a.ioErrors++
            code != null -> when (code) {
                in 200..299 -> a.s2++
                in 300..399 -> a.s3++
                in 400..499 -> a.s4++
                else -> a.s5++
            }
        }
        when (source) {
            CacheSource.CACHE -> a.cache++
            CacheSource.CONDITIONAL -> a.conditional++
            CacheSource.SYNTHETIC_504 -> a.synthetic++
            else -> {}
        }
        if (o.attempts > 1) {
            a.retriedCalls++
            a.totalRetries += o.attempts - 1
        }
        a.maxMs = maxOf(a.maxMs, o.durationMs)
        a.totalMs += o.durationMs
    }

    // Image hosts are chatty: only the surprising calls get a line.
    private fun worthLogging(o: CallOutcome, source: CacheSource): Boolean {
        if (o.canceled) return false
        if (o.error != null || source == CacheSource.SYNTHETIC_504) return true
        val code = o.response?.code ?: return true
        return code !in 200..299 || o.durationMs > SLOW_MS
    }

    private fun level(o: CallOutcome): Level {
        if (o.canceled) return Level.I
        val code = o.response?.code ?: return Level.W
        return if (o.error == null && code in 200..399) Level.I else Level.W
    }

    private fun line(o: CallOutcome, source: CacheSource): String {
        val result = when {
            o.canceled -> "CANCELED"
            o.error != null -> "ERR:${o.error.javaClass.simpleName}"
            else -> (o.response?.code ?: "?").toString()
        }
        val sb = StringBuilder()
        sb.append('[').append(o.actionId ?: "-").append("] ")
            .append(o.request.method).append(' ').append(describeUrl(o.request.url))
            .append(" -> ").append(result).append(' ').append(source.name).append(' ')
            .append(o.durationMs).append("ms tries=").append(o.attempts)
        if (o.attempts > 1 && o.codes.isNotEmpty()) sb.append(" codes=").append(o.codes.joinToString(","))
        if (o.response?.request?.cacheControl?.onlyIfCached == true) sb.append(" offline=1")
        return sb.toString()
    }
}
