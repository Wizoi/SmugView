package com.smugview.app.util

import io.ktor.http.Url

/** The host and the decoded path of a web address, without `java.net.URI`. Null when the text is not an address. */
object UrlParts {
    fun hostOrNull(address: String): String? =
        try { Url(address).host.ifBlank { null } } catch (e: Exception) { null }

    /** `/a/b` for `https://h/a/b/` (no trailing slash), or null when the text is not an address. */
    fun pathOrNull(address: String): String? =
        try { "/" + Url(address).segments.joinToString("/") } catch (e: Exception) { null }

    private const val UNRESERVED = "-_.!~*'()"
    private const val HEX = "0123456789ABCDEF"

    /** Percent-encodes [text] as one path segment, leaving letters, digits and `-_.!~*'()` alone (what `android.net.Uri.encode` does). */
    fun encode(text: String): String {
        val out = StringBuilder()
        for (byte in text.encodeToByteArray()) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED) {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[(byte.toInt() shr 4) and 0xF])
                out.append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    /** [address] with `name=value` added to its query (before any `#fragment`), unless the query already has a `name` parameter. */
    fun withQueryParameter(address: String, name: String, value: String): String {
        val hash = address.indexOf('#')
        val beforeFragment = if (hash >= 0) address.substring(0, hash) else address
        val fragment = if (hash >= 0) address.substring(hash) else ""
        val queryStart = beforeFragment.indexOf('?')
        if (queryStart >= 0) {
            val has = beforeFragment.substring(queryStart + 1).split('&').any { it.substringBefore('=') == name }
            if (has) return address
        }
        val separator = when {
            queryStart < 0 -> "?"
            beforeFragment.endsWith("?") || beforeFragment.endsWith("&") -> ""
            else -> "&"
        }
        return beforeFragment + separator + name + "=" + encode(value) + fragment
    }
}
