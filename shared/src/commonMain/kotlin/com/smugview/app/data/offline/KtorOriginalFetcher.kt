package com.smugview.app.data.offline

import com.smugview.app.util.Md5
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The iOS [OriginalFetcher] (also runs on the JVM, for the tests): one GET through Ktor, streamed to the part file in 64 KB chunks with the
 * MD5 taken as the bytes go by, the same report as `OkHttpOriginalFetcher`. [client] must be a cache-less client (R-37) and must not throw
 * on a non-2xx status (Ktor's default). Redirects are followed by Ktor. Adds nothing to the URL: no API key, no password.
 */
class KtorOriginalFetcher(
    private val client: HttpClient,
    private val fs: FileOps = createPlatformFileOps()
) : OriginalFetcher {

    override suspend fun fetch(url: String, part: String): FetchResult {
        // Ktor's parser accepts almost anything ("not a url" becomes http://localhost), so check the scheme ourselves.
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return FetchResult.NoSource
        val parsed = try { Url(url) } catch (e: Exception) { return FetchResult.NoSource }
        if (parsed.host.isEmpty()) return FetchResult.NoSource
        return try {
            client.prepareGet(parsed).execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute FetchResult.HttpError(response.status.value, response.headers[HttpHeaders.RetryAfter], syntheticCacheMiss = false)
                }
                val declared = response.contentLength() ?: -1L
                val contentType = response.headers[HttpHeaders.ContentType]
                val channel = response.bodyAsChannel()
                val md5 = Md5()
                var total = 0L
                val out = fs.writer(part, append = false)
                try {
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        md5.update(buf, 0, n)
                        total += n
                    }
                } finally {
                    out.close()
                }
                if (declared >= 0 && total != declared) {
                    // The connection dropped (or the server cut the body) before the whole body arrived.
                    FetchResult.Broken(IOException("body ended at $total of $declared"), wroteBytes = total > 0)
                } else {
                    FetchResult.Ok(total, md5.hex(), contentType)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No network, a reset, a TLS failure, a full disk: all "the transfer broke". The downloader maps the reason.
            FetchResult.Broken(e, wroteBytes = runCatching { fs.length(part) > 0 }.getOrDefault(false))
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
