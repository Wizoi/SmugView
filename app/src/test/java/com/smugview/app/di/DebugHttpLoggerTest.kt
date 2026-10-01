package com.smugview.app.di

import com.smugview.app.diag.Redactor
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** R-52: the debug HTTP logger must not print the API key, passwords or cookies. */
class DebugHttpLoggerTest {
    private val apiKey = "TESTAPIKEY1234"
    private val password = "hunter22xyz"
    private val requestCookie = "sessionreqCOOKIE"
    private val responseCookie = "sessionrespCOOKIE"

    private fun serveOnce(): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val r = s.getInputStream()
                    // Read headers, then the (small) body that follows, without blocking on EOF.
                    val buf = ByteArray(8192)
                    s.soTimeout = 500
                    runCatching { r.read(buf) }
                    val body = """{"Password":"$password","ok":true}"""
                    s.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nSet-Cookie: JSESSION=$responseCookie; Path=/\r\n" +
                                "Content-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray()
                        )
                        flush()
                    }
                }
            }
        }
        return server to server.localPort
    }

    @Test fun apiKeyPasswordAndCookiesNeverReachTheSink() {
        val redactor = Redactor().also { it.setSecrets(listOf(apiKey, password)) }
        val captured = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor(debugHttpLogger(redactor) { captured.add(it) }).build()
        val (server, port) = serveOnce()

        val request = Request.Builder()
            .url("http://localhost:$port/api/v2/node/abc!unlock?APIKey=$apiKey")
            .header("Cookie", "JSESSION=$requestCookie")
            .header("Authorization", "Bearer sometoken12345")
            .post(FormBody.Builder().add("Password", password).add("x", "1").build())
            .build()
        client.newCall(request).execute().use { it.body!!.string() }
        server.close()

        val text = captured.joinToString("\n")
        assertTrue("logger printed nothing:\n$text", text.contains("/api/v2/node/abc!unlock"))
        for (secret in listOf(apiKey, password, requestCookie, responseCookie, "sometoken12345")) {
            assertFalse("leaked $secret in:\n$text", text.contains(secret))
        }
    }
}
