package com.smugview.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SmugMugErrorMapper] — a pure function that maps HTTP codes and response bodies
 * to user-facing messages. Previously untested despite substantial branching.
 */
class SmugMugErrorMapperTest {

    // --- OAuth problem strings (checked before any JSON parsing) ---

    @Test
    fun consumerKeyUnknown_mapsToInvalidApiKey() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(
            401, "Unauthorized", "oauth_problem=consumer_key_unknown"
        )
        assertTrue(msg.contains("Invalid API Key"))
    }

    @Test
    fun tokenRejectedOrExpired_mapsToSessionExpired() {
        val rejected = SmugMugErrorMapper.getFriendlyMessage(401, "x", "oauth_problem=token_rejected")
        val expired = SmugMugErrorMapper.getFriendlyMessage(401, "x", "oauth_problem=token_expired")
        assertTrue(rejected.contains("Session Expired"))
        assertTrue(expired.contains("Session Expired"))
    }

    @Test
    fun signatureInvalid_mentionsClock() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(401, "x", "oauth_problem=signature_invalid")
        assertTrue(msg.contains("Authentication Failed"))
        assertTrue(msg.contains("time and date"))
    }

    @Test
    fun lacksV2Access_mapsToTermsOfService() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(403, "x", "API key lacks v2 access")
        assertTrue(msg.contains("Terms of Service"))
    }

    @Test
    fun deepPaginationError_mapsToFriendlyHintNotRawJson() {
        // SmugMug's search backend returns an Elasticsearch error body when paging past ~10k.
        val body = """{"error":{"root_cause":[{"type":"illegal_argument_exception","reason":"Result window is too large, from + size must be less than or equal to: [10000]"}]},"status":400}"""
        val msg = SmugMugErrorMapper.getFriendlyMessage(400, "Bad Request", body)
        assertTrue(msg.contains("too many results", ignoreCase = true))
        assertTrue(!msg.contains("root_cause"))
    }

    // --- JSON Message parsing (needs real org.json) ---

    @Test
    fun passwordProtectedMessage_mapsToPasswordRequired() {
        val body = """{"Message":"This album is Password-protected."}"""
        val msg = SmugMugErrorMapper.getFriendlyMessage(403, "Forbidden", body)
        assertTrue(msg.contains("Password Required"))
    }

    @Test
    fun permissionDeniedMessage_mapsToAccessDenied() {
        val body = """{"Message":"Permission denied for this resource."}"""
        val msg = SmugMugErrorMapper.getFriendlyMessage(403, "Forbidden", body)
        assertTrue(msg.contains("Access Denied"))
    }

    @Test
    fun genericJsonMessage_isReturnedVerbatim() {
        val body = """{"Message":"Something specific went wrong."}"""
        val msg = SmugMugErrorMapper.getFriendlyMessage(400, "Bad Request", body)
        assertEquals("Something specific went wrong.", msg)
    }

    // --- HTTP status fallbacks ---

    @Test
    fun nullBody_fallsBackToHttpCodeMessage() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(404, "Not Found", null)
        assertTrue(msg.contains("Not Found"))
    }

    @Test
    fun rateLimit_fallsBackTo429Message() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(429, "Too Many Requests", null)
        assertTrue(msg.contains("Rate Limit"))
    }

    @Test
    fun unknownCode_includesCodeAndHttpMessage() {
        val msg = SmugMugErrorMapper.getFriendlyMessage(418, "I'm a teapot", null)
        assertTrue(msg.contains("418"))
        assertTrue(msg.contains("teapot"))
    }

    @Test
    fun nonJsonBodyWithUnknownCode_fallsBackGracefully() {
        // Body is non-empty, matches no OAuth string, and isn't valid JSON -> HTTP fallback.
        val msg = SmugMugErrorMapper.getFriendlyMessage(500, "Server Error", "<html>oops</html>")
        assertTrue(msg.contains("Internal Server Error"))
    }
}
