package com.smugview.app.data.offline

import com.smugview.app.data.api.isSyntheticCacheMiss
import com.smugview.app.util.Md5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches originals through the cache-less `@Named("images")` call factory ([images], R-37); blocking reads run on [io]. */
class OkHttpOriginalFetcher(
    private val images: Call.Factory,
    private val fs: FileOps = createPlatformFileOps(),
    private val io: CoroutineDispatcher = Dispatchers.IO
) : OriginalFetcher {

    override suspend fun fetch(url: String, part: String): FetchResult {
        val httpUrl = url.toHttpUrlOrNull() ?: return FetchResult.NoSource
        val call = images.newCall(Request.Builder().url(httpUrl).get().build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            return FetchResult.Broken(e, wroteBytes = false)
        }
        response.use { r ->
            if (!r.isSuccessful) return FetchResult.HttpError(r.code, r.header("Retry-After"), r.isSyntheticCacheMiss())
            val body = r.body ?: return FetchResult.NoSource
            val contentType = r.header("Content-Type")
            val copied = try {
                copyBody(body, part, call)
            } catch (e: IOException) {
                return FetchResult.Broken(e, wroteBytes = fs.length(part) > 0)
            }
            val declared = body.contentLength()
            if (declared >= 0 && copied.first != declared) {
                // The connection dropped before the whole body arrived.
                return FetchResult.Broken(IOException("body ended at ${copied.first} of $declared"), wroteBytes = true)
            }
            return FetchResult.Ok(copied.first, copied.second, contentType)
        }
    }

    /**
     * Copies [body] into [part] in 64 KB chunks on [io], with a cancellation check between chunks. A cancel while
     * the read is blocked on the socket cancels [call], which makes the read throw, so the thread is not stuck.
     */
    private suspend fun copyBody(body: ResponseBody, part: String, call: Call): Pair<Long, String> = coroutineScope {
        val copier = async(io) {
            val md5 = Md5()
            var total = 0L
            body.byteStream().use { input ->
                val out = fs.writer(part, append = false)
                try {
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md5.update(buf, 0, n)
                        total += n
                    }
                } finally {
                    out.close()
                }
            }
            total to md5.hex()
        }
        try {
            copier.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
