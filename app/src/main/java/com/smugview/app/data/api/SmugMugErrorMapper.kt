package com.smugview.app.data.api

import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.text.UserMessages
import org.json.JSONObject

object SmugMugErrorMapper {
    /**
     * Map of standard HTTP and SmugMug-specific error codes to user-friendly messages.
     */
    private val errorCodesMap = mapOf(
        400 to "Bad Request: The application sent invalid request parameters to SmugMug.",
        401 to "Unauthorized access: Please check your API key, login session, or gallery password.",
        403 to "Forbidden: You do not have permission to view this content or accept the terms of service.",
        404 to "Not Found: The requested folder, album, or photo could not be located.",
        429 to "Rate Limit Exceeded: Too many requests sent to SmugMug. Please wait a moment.",
        500 to "SmugMug Internal Server Error: SmugMug is having issues on their end. Please try again later.",
        502 to "Bad Gateway: SmugMug servers received an invalid response from upstream.",
        503 to "Service Unavailable: SmugMug servers are temporarily overloaded or down for maintenance.",
        504 to "Gateway Timeout: SmugMug servers took too long to respond to our request."
    )

    /**
     * Whether a failure at this HTTP status should surface a user-facing Toast, vs. just being
     * logged. 404 is excluded: SmugMug returns it as the EXPECTED signal for a locked folder (see
     * the "HTTP 404 Not Found on Password Folder Queries" gotcha in docs/SMUGMUG.md) — the app
     * already handles that gracefully by prompting for a password, so popping "Not Found" on that
     * normal flow was noise, not a real error worth interrupting the user for.
     */
    fun shouldShowToast(code: Int): Boolean = code != 404

    /** Same rule, given the whole response: an offline cache miss is never a toast. */
    fun shouldShowToast(response: okhttp3.Response): Boolean =
        !response.isSyntheticCacheMiss() && shouldShowToast(response.code)

    /**
     * What a screen shows for [error] as one line: its kind's heading and sentence from [UserMessages] (design 5),
     * never the exception's own text. [fallback] only when there is no error at all. A caller that knows the
     * failing thing sits under a locked root uses [Problem.from] with `underLockedRoot` instead.
     */
    fun userMessage(error: Throwable?, fallback: String, subject: Subject = Subject.Gallery): String =
        if (error == null) fallback else UserMessages.line(Problem.from(error, subject))

    /**
     * True when [error] means "this phone cannot reach SmugMug right now": no network at all, or OkHttp's synthetic
     * 504 for an `only-if-cached` miss, or a locked gallery whose saved password could not be tried for that reason.
     * A 429, a 5xx and a 404 are NOT offline: SmugMug answered.
     */
    fun isOffline(error: Throwable?): Boolean = when (error) {
        is java.io.IOException -> true
        is retrofit2.HttpException -> error.response()?.raw()?.isSyntheticCacheMiss() == true
        is com.smugview.app.data.repository.AlbumLockedException ->
            error.pendingReason == com.smugview.app.data.repository.TransientReason.Offline
        else -> false
    }

    /** True only when SmugMug explicitly refused the credentials (401/403). Offline, 429, 5xx and
     *  OkHttp's synthetic 504 say nothing about whether a saved password is still correct. */
    fun isPasswordRejection(error: Throwable?): Boolean =
        error is retrofit2.HttpException && (error.code() == 401 || error.code() == 403)

    /**
     * Extracts and maps a friendly error message from the response code and body content.
     */
    fun getFriendlyMessage(code: Int, httpMessage: String, responseBodyContent: String?): String {
        if (!responseBodyContent.isNullOrEmpty()) {
            // 1. Detect OAuth-specific failures
            when {
                responseBodyContent.contains("oauth_problem=consumer_key_unknown") -> 
                    return "Invalid API Key: Please verify your API Key in your app settings."
                responseBodyContent.contains("oauth_problem=token_rejected") || 
                responseBodyContent.contains("oauth_problem=token_expired") -> 
                    return "Session Expired: Your SmugMug login session has expired. Please log in again."
                responseBodyContent.contains("oauth_problem=signature_invalid") -> 
                    return "Authentication Failed: OAuth signature mismatch. Please ensure your device's time and date settings are correct."
                responseBodyContent.contains("API key lacks v2 access") -> 
                    return "Terms of Service Needed: Your API key lacks v2 access. Please accept the SmugMug developer terms at https://api.smugmug.com/api/developer/accept"
                responseBodyContent.contains("expired_token") ->
                    return "Authorization Expired: Your developer token is expired. Please re-authenticate."
                // SmugMug's search is Elasticsearch-backed and refuses deep pagination past
                // ~10,000 results ("Result window is too large"). Show a friendly hint instead
                // of the raw error JSON.
                responseBodyContent.contains("Result window is too large", ignoreCase = true) ->
                    return "That search has too many results to load them all. Try narrowing it with more specific tags."
            }

            // 2. Parse JSON error response if present
            try {
                val json = JSONObject(responseBodyContent)
                val apiMessage = json.optString("Message")
                if (apiMessage.isNotEmpty()) {
                    when {
                        apiMessage.contains("Password-protected", ignoreCase = true) -> 
                            return "Password Required: This album is password-protected. Please enter the correct password."
                        apiMessage.contains("Permission denied", ignoreCase = true) -> 
                            return "Access Denied: You do not have permissions to access this folder/album."
                        apiMessage.contains("invalid key", ignoreCase = true) ->
                            return "Invalid API Key: The key provided is invalid."
                    }
                    return apiMessage
                }
            } catch (e: Exception) {
                // Parsing failed, proceed to fallback
            }
        }

        // 3. Fallback to standard HTTP status codes
        return errorCodesMap[code] ?: "Network Request Failed (Code $code): $httpMessage"
    }
}
