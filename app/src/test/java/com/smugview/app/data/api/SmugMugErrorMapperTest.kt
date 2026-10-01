package com.smugview.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import okhttp3.ResponseBody.Companion.toResponseBody
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

    // --- shouldShowToast ---

    @Test
    fun shouldShowToast_falseFor404() {
        // SmugMug returns 404 as the expected signal for a locked folder, which the app already
        // handles gracefully via the password prompt — not worth interrupting the user for.
        assertEquals(false, SmugMugErrorMapper.shouldShowToast(404))
    }

    @Test
    fun shouldShowToast_trueForOtherErrorCodes() {
        for (code in listOf(400, 401, 403, 429, 500, 502, 503, 504)) {
            assertEquals("code $code should still Toast", true, SmugMugErrorMapper.shouldShowToast(code))
        }
    }

    @Test
    fun isPasswordRejection_onlyFor401And403() {
        fun http(code: Int) = retrofit2.HttpException(
            retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, "{}"))
        )
        assertTrue(SmugMugErrorMapper.isPasswordRejection(http(401)))
        assertTrue(SmugMugErrorMapper.isPasswordRejection(http(403)))
        for (code in listOf(404, 429, 500, 503, 504)) {
            assertEquals("HTTP $code is not a rejection", false, SmugMugErrorMapper.isPasswordRejection(http(code)))
        }
        assertEquals(false, SmugMugErrorMapper.isPasswordRejection(java.io.IOException("offline")))
        assertEquals(false, SmugMugErrorMapper.isPasswordRejection(null))
    }

    // --- findings #6: the offline message ---

    private fun httpException(code: Int, withNetworkResponse: Boolean): retrofit2.HttpException {
        val req = okhttp3.Request.Builder().url("https://api.smugmug.com/api/v2/node/zz9!children").build()
        fun raw(c: Int) = okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(c).message("m")
        val rawResponse = if (withNetworkResponse) raw(code).networkResponse(raw(code).build()).build() else raw(code).build()
        val body = "".toResponseBody(null)
        return retrofit2.HttpException(retrofit2.Response.error<Any>(body, rawResponse))
    }

    @Test
    fun syntheticCacheMiss_getsTheOfflineMessage() {
        val msg = SmugMugErrorMapper.userMessage(httpException(504, withNetworkResponse = false), "fallback")
        assertEquals("You're offline, and this hasn't been opened on this device yet.", msg)
    }

    @Test
    fun realGatewayTimeout_keepsItsOwnMessage() {
        val e = httpException(504, withNetworkResponse = true)
        assertEquals(e.localizedMessage, SmugMugErrorMapper.userMessage(e, "fallback"))
    }

    @Test
    fun otherErrors_useTheirMessageThenTheFallback() {
        assertEquals("boom", SmugMugErrorMapper.userMessage(java.io.IOException("boom"), "fallback"))
        assertEquals("fallback", SmugMugErrorMapper.userMessage(RuntimeException(null as String?), "fallback"))
        assertEquals("fallback", SmugMugErrorMapper.userMessage(null, "fallback"))
    }
}
