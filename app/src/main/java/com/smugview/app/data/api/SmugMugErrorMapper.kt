package com.smugview.app.data.api

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
