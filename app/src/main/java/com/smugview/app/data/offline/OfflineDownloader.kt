package com.smugview.app.data.offline

import com.smugview.app.data.api.isSyntheticCacheMiss
import com.smugview.app.data.db.OfflineFile
import com.smugview.app.data.db.OfflineGallery
import com.smugview.app.data.repository.AlbumLockedException
import com.smugview.app.data.repository.ImageSource
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the pass runs on. [UNMETERED] (Wi-Fi): every wanted file. [ANY] (any connected network, maybe mobile data):
 * only the files whose `wifiOnly` is off (Q3: single photos and galleries the user allowed on mobile data).
 */
enum class NetworkClass { ANY, UNMETERED }

/**
 * @property more the pass stopped with files still due (the budget ran out): the scheduler appends another run.
 * @property stoppedFor why it stopped early, or null when it ran out of work or time.
 */
data class PassResult(
    val pass: Int,
    val downloaded: Int,
    val failed: Int,
    val bytes: Long,
    val more: Boolean,
    val stoppedFor: FailureReason? = null
)

/**
 * One pass over the wanted files (phase 5 design 2.4): first the kept galleries that need listing, then the files. Everything that can go
 * wrong is mapped by [DownloadFailure]; everything that touches a file or its row goes through [OfflineStore].
 *
 *  - One process-wide [Mutex]: two works never download at once, and a second pass for the same key finds it DONE.
 *  - [kotlinx.coroutines.CancellationException] always propagates. A cancelled file goes back to PENDING and its
 *    `.part` is deleted (in `NonCancellable`), then it is rethrown.
 *  - A retryable failure never becomes DONE (R-40: the old worker wrote "done, empty path" after 3 tries).
 *  - Fetching goes through the cache-less `@Named("images")` factory ([images]); blocking I/O runs on [io].
 */
class OfflineDownloader(
    private val store: OfflineStore,
    private val repo: SmugMugRepository,
    private val images: Call.Factory,
    private val apiKey: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Fetched(val bytes: Long, val md5: String, val contentType: String?)

    private sealed interface Fetch {
        class Ok(val fetched: Fetched) : Fetch
        class Fail(val failure: Failure) : Fetch
    }

    private sealed interface Resolved {
        class Ok(val source: ImageSource) : Resolved
        class Fail(val failure: Failure) : Resolved
    }

    private enum class Step { DONE, FAILED, SKIPPED, STOP }

    suspend fun runPass(network: NetworkClass, budgetMs: Long = DEFAULT_BUDGET_MS): PassResult = PASS_LOCK.withLock {
        val pass = PASS_COUNTER.incrementAndGet()
        val started = clock()
        store.ensureRecovered()
        store.collectGarbage()
        store.sweepOrphans()

        val unmetered = network == NetworkClass.UNMETERED
        val tried = HashSet<String>()
        var downloaded = 0
        var failed = 0
        var bytes = 0L
        var consecutiveOffline = 0
        var stoppedFor: FailureReason? = null
        var more = listGalleries(unmetered, started, budgetMs)
        // Once no gallery is waiting to be listed, the old offline_photos/ directory has nothing left to give (5-8).
        runCatching { store.cleanLegacyFiles() }
        while (true) {
            val next = nextRow(unmetered, tried)
            if (next == null) break
            if (clock() - started >= budgetMs) { more = true; break }
            tried += next.fileKey
            when (downloadOne(next)) {
                Step.DONE -> { downloaded++; bytes += store.file(next.fileKey)?.bytes ?: 0L; consecutiveOffline = 0 }
                Step.SKIPPED -> {}
                Step.FAILED -> {
                    failed++
                    val after = store.file(next.fileKey)
                    consecutiveOffline = if (after?.failure == FailureReason.OFFLINE.name) consecutiveOffline + 1 else 0
                    if (consecutiveOffline >= OFFLINE_STOP_AFTER) { stoppedFor = FailureReason.OFFLINE; break }
                }
                Step.STOP -> { failed++; stoppedFor = FailureReason.STORAGE_FULL; break }
            }
        }
        PassResult(pass, downloaded, failed, bytes, more, stoppedFor)
    }

    /**
     * Design 2.4 step 3: lists the kept galleries that need it (never listed, a retryable failure that is due, or
     * changed since the last listing), at most [OfflineStore.GALLERIES_PER_PASS] per pass and each at most once.
     * Returns true when more were left for the next run. A gallery's photos are listed completely or not at all
     * (a page that fails leaves the earlier listing, if any, untouched); what the listing adds is only PENDING,
     * never DONE (5-7: offline or 429 half-way never leaves a row Done).
     */
    private suspend fun listGalleries(unmetered: Boolean, started: Long, budgetMs: Long): Boolean {
        val tried = HashSet<Pair<Long, String>>()
        var more = false
        var changed = false
        while (true) {
            val next = store.galleriesToList(unmetered, tried.size + 1)
                .firstOrNull { (it.collectionId to it.albumKey) !in tried } ?: break
            if (tried.size >= OfflineStore.GALLERIES_PER_PASS || clock() - started >= budgetMs) { more = true; break }
            tried += next.collectionId to next.albumKey
            val outcome = listOne(next)
            if (outcome == ListOutcome.LISTED) changed = true
            if (outcome == ListOutcome.OFFLINE) break
        }
        // Photos a re-listing dropped are garbage now (space comes back in the same pass).
        if (changed) store.collectGarbage()
        return more
    }

    private enum class ListOutcome { LISTED, FAILED, OFFLINE }

    private suspend fun listOne(gallery: OfflineGallery): ListOutcome {
        val key = gallery.albumKey
        var sessionTransient = false
        try {
            val ilu = store.cachedImagesLastUpdated(key)
            // The saved password is tried here once; a gallery under no password answers Rejected (no change) and is
            // listed anyway. A password is never deleted by this (R-21).
            if (repo.unlocks.ensureSession(key, apiKey()) == SmugMugRepository.UnlockResult.Transient) sessionTransient = true
            val images = repo.getAllAlbumImages(key, apiKey(), password = null, cacheControl = "no-cache")
            val applied = store.applyListing(
                gallery,
                images.map {
                    OfflineStore.ListedImage(
                        imageKey = it.imageKey,
                        sourceUrl = it.archivedUri?.takeIf { u -> u.isNotEmpty() },
                        bytes = it.archivedSize?.takeIf { s -> s > 0 },
                        md5 = it.archivedMd5?.takeIf { m -> m.isNotEmpty() }?.lowercase(),
                        title = it.title?.takeIf { t -> t.isNotBlank() },
                        thumbnailUrl = it.thumbnailUrl,
                        format = it.format,
                        dateTaken = it.date
                    )
                },
                ilu
            )
            // Photos the pre-17 code downloaded are adopted by key now that their size is known (5-8). The listing is
            // already recorded, so nothing here may fail it.
            if (applied) try { store.adoptLegacyFiles(images.map { it.imageKey }) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            return ListOutcome.LISTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlbumLockedException) {
            store.failGallery(gallery, DownloadFailure.locked(transient = e.pendingReason != null || sessionTransient))
            return ListOutcome.FAILED
        } catch (e: HttpException) {
            val code = e.code()
            val raw = e.response()?.raw()
            val failure = if (code == 401 || code == 403) DownloadFailure.locked(transient = false)
            else DownloadFailure.classify(
                code, raw?.headers, null, wroteBytes = false,
                syntheticCacheMiss = raw?.isSyntheticCacheMiss() == true, reResolved = true, nowMs = clock()
            ) ?: Failure(FailureReason.UNEXPECTED, retryable = false, httpCode = code)
            store.failGallery(gallery, failure)
            return if (failure.reason == FailureReason.OFFLINE) ListOutcome.OFFLINE else ListOutcome.FAILED
        } catch (e: IOException) {
            store.failGallery(gallery, DownloadFailure.classify(null, null, e, wroteBytes = false, nowMs = clock())!!)
            return ListOutcome.OFFLINE
        } catch (e: Exception) {
            // A bug, not a network event: visible, not retried by itself.
            store.failGallery(gallery, Failure(FailureReason.UNEXPECTED, retryable = false))
            return ListOutcome.FAILED
        }
    }

    /** The oldest wanted row this pass has not tried yet. */
    private suspend fun nextRow(unmetered: Boolean, tried: Set<String>): OfflineFile? =
        store.candidates(clock(), unmetered, tried.size + 1).firstOrNull { it.fileKey !in tried }

    private suspend fun downloadOne(row: OfflineFile): Step {
        val fileKey = row.fileKey
        if (!store.hasRoomFor(row.expectedBytes)) {
            store.fail(fileKey, DownloadFailure.storageFull())
            return Step.STOP
        }
        val write = store.beginWrite(fileKey) ?: return Step.SKIPPED
        try {
            var current = row
            var reResolved = false

            if (current.sourceUrl == null) {
                when (val r = resolve(current, fresh = false)) {
                    is Resolved.Fail -> { currentCoroutineContext().ensureActive(); store.fail(fileKey, r.failure, write.part); return Step.FAILED }
                    is Resolved.Ok -> current = applySource(current, r.source)
                        ?: run { store.fail(fileKey, DownloadFailure.noSource(), write.part); return Step.FAILED }
                }
                if (!store.hasRoomFor(current.expectedBytes)) {
                    store.fail(fileKey, DownloadFailure.storageFull(), write.part)
                    return Step.STOP
                }
            }

            while (true) {
                when (val f = fetch(current, write.part, reResolved)) {
                    is Fetch.Ok -> {
                        val bad = DownloadFailure.verify(
                            current.expectedBytes, f.fetched.bytes, current.md5, f.fetched.md5,
                            alreadyRetried = row.failure == FailureReason.DAMAGED.name
                        )
                        if (bad != null) { store.fail(fileKey, bad, write.part); return Step.FAILED }
                        val ext = OfflineStore.extensionFor(f.fetched.contentType, current.sourceUrl)
                        return try {
                            when (store.commit(write, ext, f.fetched.bytes)) {
                                is OfflineStore.CommitResult.Done -> Step.DONE
                                OfflineStore.CommitResult.Unreferenced -> Step.SKIPPED
                            }
                        } catch (e: IOException) {
                            // The rename or the sync failed: not the network's fault, and not worth a silent loop.
                            store.fail(fileKey, Failure(FailureReason.UNEXPECTED, retryable = true), write.part)
                            Step.FAILED
                        }
                    }
                    is Fetch.Fail -> {
                        // A cancelled call surfaces as an IOException: that is a cancel, not an OFFLINE failure.
                        currentCoroutineContext().ensureActive()
                        val failure = f.failure
                        if (failure.reResolve && !reResolved) {
                            // A 401/403 from the CDN: the link may have changed. Look it up again, once.
                            reResolved = true
                            when (val r = resolve(current, fresh = true)) {
                                is Resolved.Fail -> { currentCoroutineContext().ensureActive(); store.fail(fileKey, r.failure, write.part); return Step.FAILED }
                                is Resolved.Ok -> {
                                    current = applySource(current, r.source)
                                        ?: run { store.fail(fileKey, DownloadFailure.noSource(), write.part); return Step.FAILED }
                                    continue
                                }
                            }
                        }
                        store.fail(fileKey, failure, write.part)
                        return if (failure.reason == FailureReason.STORAGE_FULL) Step.STOP else Step.FAILED
                    }
                }
            }
        } catch (e: CancellationException) {
            store.abandon(write)
            throw e
        } catch (e: Exception) {
            // A bug, not a network event. Visible and not retried by itself; never swallows a cancellation (above).
            store.fail(fileKey, Failure(FailureReason.UNEXPECTED, retryable = false), write.part)
            return Step.FAILED
        }
    }

    /** Stores what a resolve found; null when it has no usable source. */
    private suspend fun applySource(row: OfflineFile, source: ImageSource): OfflineFile? {
        val uri = source.archivedUri ?: return null
        store.updateSource(row.fileKey, uri, source.archivedSize, source.archivedMd5, source.format)
        return row.copy(
            sourceUrl = uri,
            expectedBytes = source.archivedSize ?: row.expectedBytes,
            md5 = source.archivedMd5 ?: row.md5,
            format = source.format ?: row.format
        )
    }

    /** `image/{key}-0` (P8). A 401/403 goes through `ensureSession` for the gallery once, then LOCKED. */
    private suspend fun resolve(row: OfflineFile, fresh: Boolean): Resolved {
        var unlocked = false
        while (true) {
            try {
                return Resolved.Ok(repo.resolveImageSource(row.imageKey, apiKey(), fresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val code = e.code()
                if ((code == 401 || code == 403)) {
                    val album = row.albumKey
                    if (album == null || unlocked) return Resolved.Fail(DownloadFailure.locked(transient = false))
                    unlocked = true
                    when (repo.unlocks.ensureSession(album, apiKey())) {
                        SmugMugRepository.UnlockResult.Success -> continue
                        SmugMugRepository.UnlockResult.Rejected -> return Resolved.Fail(DownloadFailure.locked(transient = false))
                        SmugMugRepository.UnlockResult.Transient -> return Resolved.Fail(DownloadFailure.locked(transient = true))
                    }
                }
                // E1 (live, 5-11): a photo in a password gallery is a 404, not a 401/403, on `image/{key}-0`
                // without the session. A 404 is "removed" only once the saved password (if any) was tried:
                // with no password to try (Rejected) it stays GONE, as the design says.
                if (code == 404 && !unlocked && row.albumKey != null) {
                    unlocked = true
                    when (repo.unlocks.ensureSession(row.albumKey, apiKey())) {
                        SmugMugRepository.UnlockResult.Success -> continue
                        SmugMugRepository.UnlockResult.Transient -> return Resolved.Fail(DownloadFailure.locked(transient = true))
                        SmugMugRepository.UnlockResult.Rejected -> Unit
                    }
                }
                val raw = e.response()?.raw()
                return Resolved.Fail(
                    DownloadFailure.classify(
                        code, raw?.headers, null, wroteBytes = false,
                        syntheticCacheMiss = raw?.isSyntheticCacheMiss() == true, reResolved = true, nowMs = clock()
                    ) ?: Failure(FailureReason.UNEXPECTED, retryable = false, httpCode = code)
                )
            } catch (e: IOException) {
                return Resolved.Fail(DownloadFailure.classify(null, null, e, wroteBytes = false, nowMs = clock())!!)
            }
        }
    }

    /** One GET of the original into [part]. Returns what arrived, or the classified failure. */
    private suspend fun fetch(row: OfflineFile, part: File, reResolved: Boolean): Fetch {
        val url = row.sourceUrl?.toHttpUrlOrNull() ?: return Fetch.Fail(DownloadFailure.noSource())
        val call = images.newCall(Request.Builder().url(url).get().build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            return Fetch.Fail(DownloadFailure.classify(null, null, e, wroteBytes = false, nowMs = clock())!!)
        }
        response.use { r ->
            if (!r.isSuccessful) {
                return Fetch.Fail(
                    DownloadFailure.classify(
                        r.code, r.headers, null, wroteBytes = false,
                        syntheticCacheMiss = r.isSyntheticCacheMiss(), reResolved = reResolved, nowMs = clock()
                    ) ?: Failure(FailureReason.UNEXPECTED, retryable = false, httpCode = r.code)
                )
            }
            val body = r.body ?: return Fetch.Fail(DownloadFailure.noSource())
            val contentType = r.header("Content-Type")
            val copied = try {
                copyBody(body, part, call)
            } catch (e: IOException) {
                return Fetch.Fail(DownloadFailure.classify(null, null, e, wroteBytes = part.length() > 0, nowMs = clock())!!)
            }
            val declared = body.contentLength()
            if (declared >= 0 && copied.bytes != declared) {
                // The connection dropped before the whole body arrived.
                return Fetch.Fail(
                    DownloadFailure.classify(null, null, IOException("body ended at ${copied.bytes} of $declared"), wroteBytes = true, nowMs = clock())!!
                )
            }
            return Fetch.Ok(Fetched(copied.bytes, copied.md5, contentType))
        }
    }

    /**
     * Copies [body] into [part] in 64 KB chunks on [io], with a cancellation check between chunks. A cancel while
     * the read is blocked on the socket cancels [call], which makes the read throw, so the thread is not stuck.
     */
    private suspend fun copyBody(body: ResponseBody, part: File, call: Call): Fetched = coroutineScope {
        val copier = async(io) {
            val md = MessageDigest.getInstance("MD5")
            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                    }
                    out.flush()
                }
            }
            Fetched(total, md.digest().joinToString("") { "%02x".format(it) }, null)
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

    companion object {
        /** No new file starts after this (WorkManager stops a worker at 10 minutes). */
        const val DEFAULT_BUDGET_MS = 8 * 60_000L
        private const val CHUNK = 64 * 1024

        /** Two files in a row that found the network gone: the rest would fail the same way. */
        private const val OFFLINE_STOP_AFTER = 2

        private val PASS_LOCK = Mutex()
        private val PASS_COUNTER = AtomicInteger(0)
    }
}
