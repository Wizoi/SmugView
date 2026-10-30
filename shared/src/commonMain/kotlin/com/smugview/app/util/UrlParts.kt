package com.smugview.app.util

import io.ktor.http.Url

/** The host and the decoded path of a web address, without `java.net.URI`. Null when the text is not an address. */
object UrlParts {
    fun hostOrNull(address: String): String? =
        try { Url(address).host.ifBlank { null } } catch (e: Exception) { null }

    /** `/a/b` for `https://h/a/b/` (no trailing slash), or null when the text is not an address. */
    fun pathOrNull(address: String): String? =
        try { "/" + Url(address).segments.joinToString("/") } catch (e: Exception) { null }
}
