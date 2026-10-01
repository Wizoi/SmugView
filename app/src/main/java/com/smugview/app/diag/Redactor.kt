package com.smugview.app.diag

import java.net.URLEncoder

/**
 * Strips secrets and family-identifying paths from text before it is logged or exported
 * (design phase-1-observability.md §3.2). Pure Kotlin, thread-safe, idempotent.
 */
class Redactor {
    @Volatile private var secretRegex: Regex? = null

    /** Replaces the literal secrets (API key, saved passwords). Values under 4 chars are ignored. */
    fun setSecrets(values: Collection<String>) {
        val forms = LinkedHashSet<String>()
        for (v in values) {
            if (v.length < MIN_SECRET_LEN) continue
            forms.add(v)
            forms.add(URLEncoder.encode(v, "UTF-8"))
        }
        // A secret that is a substring of our own markers would break idempotence.
        forms.removeAll { MARKERS.contains(it) }
        secretRegex = if (forms.isEmpty()) null
        else Regex(forms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
    }

    fun redact(text: String, maxLineChars: Int = 4096): String {
        if (text.isEmpty()) return text
        var s = text
        if (s.length > MAX_INPUT) s = capInput(s)
        for ((regex, replacement) in RULES) s = regex.replace(s, replacement)
        s = LEN_RULE.replace(s) { m -> m.groupValues[1] + "<len=" + m.groupValues[2].length + ">" }
        secretRegex?.let { s = it.replace(s, "<secret>") }
        return capLines(s, maxLineChars)
    }

    private fun capInput(text: String): String {
        var end = MAX_INPUT
        val floor = MAX_INPUT - MAX_PARTIAL_TOKEN
        var i = end
        // Drop the trailing partial token: a cut secret would no longer match its literal.
        while (i > floor && !text[i - 1].isWhitespace() && text[i - 1] != '&') i--
        end = if (i > floor) i else floor
        return text.substring(0, end) + ELLIPSIS
    }

    private fun capLines(text: String, max: Int): String {
        if (text.length <= max) return text
        val sb = StringBuilder(text.length)
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            if (end - start > max) {
                sb.append(text, start, start + max - 1).append(ELLIPSIS)
            } else {
                sb.append(text, start, end)
            }
            if (nl < 0) break
            sb.append('\n')
            start = nl + 1
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_INPUT = 16 * 1024
        const val MAX_PARTIAL_TOKEN = 512
        const val MIN_SECRET_LEN = 4
        const val ELLIPSIS = "…"
        const val MARKERS = "<secret><r><path><len="

        val RULES: List<Pair<Regex, String>> = listOf(
            // R1 query/form/header parameters (the value may be quoted, as in OAuth headers)
            Regex("""(?i)(^|[?&;\s"'])(APIKey|api_key|Password|oauth_[a-z_]+|access_token)=("[^"]*"|'[^']*'|[^&\s#"']*)""") to "$1$2=<r>",
            // R2 percent-encoded parameter inside another URL
            Regex("""(?i)(APIKey|Password)%3D[^&%\s"']*""") to "$1%3D<r>",
            // R3 JSON fields
            Regex("""(?i)"(APIKey|Password|PasswordHint|oauth_[a-z_]+|access_token)"\s*:\s*"(?:[^"\\]|\\.)*"""") to "\"$1\":\"<r>\"",
            // R4 credential headers
            Regex("""(?im)^([ \t]*)(Cookie|Set-Cookie|Authorization|Proxy-Authorization)[ \t]*:.*$""") to "$1$2: <r>",
            // R5 bearer tokens
            Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer <r>",
            // R6 CDN photo URLs (a password-gallery CDN URL opens without a session, R-53)
            Regex("""(?i)https?://photos\.smugmug\.com/(?!<)[^\s"'<>]*""") to "https://photos.smugmug.com/<r>",
            // R7 web URLs name galleries and people
            Regex("""(?i)(https?://(?!api\.)[a-z0-9-]+\.smugmug\.com)/(?!<)[^\s"'<>]*""") to "$1/<path>",
            // R8 folder-by-path API URLs
            Regex("""(/api/v2/folder/user/[^/\s!?]+)/[^\s!?"']*""") to "$1/<path>",
        )

        // R9 search text: keep only its length
        val LEN_RULE = Regex("""(?i)([?&](?:Text|Keywords|q)=)(?!<len=)([^&\s#]*)""")
    }
}
