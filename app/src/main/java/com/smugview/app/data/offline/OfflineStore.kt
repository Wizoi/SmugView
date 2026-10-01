package com.smugview.app.data.offline

import androidx.room.withTransaction
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.OfflineFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * The only owner of offline files (phase 5, design 2.1-2.4): their names, their rows in `offline_files`, and the
 * order in which the two change. Nothing else deletes, renames or creates a file under `filesDir/offline/`.
 *
 *  - Final file: `filesDir/offline/{imageKey}.orig.{ext}`. Temp: `filesDir/offline/.tmp/{imageKey}.orig.{claim}.part`
 *    (same filesystem, so the rename is atomic).
 *  - An image key comes from the server, so it is checked against [KEY_PATTERN] before it becomes a path. One that
 *    fails is `FAILED(NO_SOURCE)` and no file is ever named from it.
 *  - Whether a file is wanted is a query ([isReferenced]), never a counter.
 *
 * Ordering rules, each pinned by `OfflineStoreTest`:
 *  - delete: the row first (guarded by "still unreferenced and not being written"), then the file. A crash between
 *    the two leaves a file with no DONE row, which [sweepOrphans] removes.
 *  - commit: `fsync` the `.part`, then one transaction: if still wanted and still ours, mark DONE and rename; else
 *    delete the `.part` and the row. A crash after the rename leaves a DOWNLOADING row and a finished file, which
 *    [recover] adopts when the size (and MD5, when known) match.
 *
 * [runId] names this process's claims: a DOWNLOADING row or a `.part` carrying another run id belongs to a process
 * that died. [freeBytes] and [clock] are seams for tests.
 */
class OfflineStore(
    private val db: AppDatabase,
    private val filesDir: File,
    val runId: String = UUID.randomUUID().toString().replace("-", ""),
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeBytes: () -> Long = { filesDir.usableSpace },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val dao get() = db.offlineDao()
    private val offlineDir = File(filesDir, DIR)
    private val tmpDir = File(offlineDir, ".tmp")

    /** Serializes everything that touches the files, so a collector never races a commit. */
    private val fileLock = Mutex()
    private val recoverLock = Mutex()
    @Volatile private var recovered = false

    /** A claimed row and the temp file it is being written to. */
    class Write internal constructor(val fileKey: String, val imageKey: String, val part: File)

    sealed interface CommitResult {
        data class Done(val relPath: String, val bytes: Long) : CommitResult
        /** Nobody wants the file any more (or the row is gone): the `.part` and the row are deleted. */
        object Unreferenced : CommitResult
    }

    // ---- requests --------------------------------------------------------------------------------------------

    /**
     * Wants this photo on the phone: `INSERT OR IGNORE`, so a row that exists wins. In particular a DONE row stays
     * DONE (fixes N4: the old REPLACE reset it). Returns the row as stored. A key that fails [KEY_PATTERN] is stored
     * as `FAILED(NO_SOURCE)`.
     */
    suspend fun request(
        imageKey: String,
        albumKey: String? = null,
        nickname: String = "",
        sourceUrl: String? = null,
        expectedBytes: Long? = null,
        md5: String? = null,
        title: String? = null,
        thumbnailUrl: String? = null,
        format: String? = null,
        dateTaken: String? = null,
        wifiOnly: Boolean = false
    ): OfflineFile {
        val now = clock()
        val valid = isValidKey(imageKey)
        val row = OfflineFile(
            fileKey = fileKeyOf(imageKey), imageKey = imageKey, albumKey = albumKey, nickname = nickname,
            sourceUrl = sourceUrl, expectedBytes = expectedBytes, md5 = md5, title = title, thumbnailUrl = thumbnailUrl,
            format = format, dateTaken = dateTaken,
            state = if (valid) PENDING else FAILED,
            failure = if (valid) null else FailureReason.NO_SOURCE.name,
            retryable = false, wifiOnly = wifiOnly, createdAt = now, updatedAt = now
        )
        dao.insertFile(row)
        return dao.getFile(row.fileKey) ?: row
    }

    suspend fun isReferenced(imageKey: String): Boolean = dao.isReferenced(imageKey)

    suspend fun unreferencedFiles(): List<OfflineFile> = dao.unreferencedFiles()

    // ---- storage ---------------------------------------------------------------------------------------------

    fun freeBytesNow(): Long = freeBytes()

    /** What the room check assumes a file needs when the listing gave no size. */
    fun needBytes(expectedBytes: Long?): Long = expectedBytes ?: UNKNOWN_SIZE_BYTES

    /** Design 2.4 step 4: after this file, would at least [FREE_FLOOR_BYTES] (Q4) still be free? */
    fun hasRoomFor(expectedBytes: Long?): Boolean = freeBytes() - needBytes(expectedBytes) >= FREE_FLOOR_BYTES

    // ---- writing ---------------------------------------------------------------------------------------------

    /**
     * Claims the row for this run (DOWNLOADING) if it is still wanted, and names its temp file. Null when the row
     * is gone, not claimable (done, permanently failed, being written), no longer referenced, or its key is bad.
     */
    suspend fun beginWrite(fileKey: String): Write? = withContext(io) {
        val row = dao.getFile(fileKey) ?: return@withContext null
        if (!isValidKey(row.imageKey)) return@withContext null
        if (dao.claim(fileKey, runId, clock()) == 0) return@withContext null
        tmpDir.mkdirs()
        val part = File(tmpDir, "${row.imageKey}.orig.$runId.part")
        part.delete()
        Write(row.fileKey, row.imageKey, part)
    }

    /**
     * Makes the finished `.part` the file. See the class comment for the order. [ext] comes from
     * [extensionFor]. Not cancellable: the file and the row must not be left half-changed.
     */
    suspend fun commit(write: Write, ext: String, bytes: Long): CommitResult =
        withContext(NonCancellable + io) {
            fileLock.withLock {
                RandomAccessFile(write.part, "rw").use { it.fd.sync() }
                var replaced: String? = null
                val result = db.withTransaction {
                    val row = dao.getFile(write.fileKey)
                    val ours = row != null && row.state == DOWNLOADING && row.claim == runId
                    if (!ours || !dao.isReferenced(write.imageKey)) {
                        write.part.delete()
                        if (ours) dao.deleteFile(write.fileKey)
                        return@withTransaction CommitResult.Unreferenced
                    }
                    val rel = relPathOf(write.imageKey, safeExt(ext))
                    val final = File(filesDir, rel)
                    replaced = row!!.relPath?.takeIf { it != rel }
                    dao.markDone(write.fileKey, runId, rel, bytes, clock())
                    // If this throws, the transaction rolls back and the row stays DOWNLOADING (the caller fails it).
                    if (!write.part.renameTo(final)) throw IOException("rename to ${final.name} failed")
                    CommitResult.Done(rel, bytes)
                }
                replaced?.let { File(filesDir, it).delete() }
                result
            }
        }

    /** Gives the claim back (cancel): the `.part` is deleted and the row is PENDING again. */
    suspend fun abandon(write: Write) = withContext(NonCancellable + io) {
        write.part.delete()
        val row = dao.getFile(write.fileKey)
        if (row != null && row.state == DOWNLOADING && row.claim == runId) dao.markPending(write.fileKey, clock())
    }

    /**
     * Records a failure on the row, deleting [part] when one was being written. `attempts` rises only when the
     * failure [Failure.countsAsAttempt]; `nextAttemptAt` comes from [DownloadFailure.nextAttemptDelayMs]. A DONE row
     * is never changed.
     */
    suspend fun fail(fileKey: String, failure: Failure, part: File? = null) = withContext(NonCancellable + io) {
        part?.delete()
        val row = dao.getFile(fileKey) ?: return@withContext
        val delay = DownloadFailure.nextAttemptDelayMs(failure, row.attempts)
        val now = clock()
        dao.markFailed(
            fileKey, failure.reason.name, failure.retryable,
            row.attempts + if (failure.countsAsAttempt) 1 else 0,
            delay?.let { now + it }, failure.httpCode, now
        )
    }

    /** What a resolve found (`image/{key}-0`). */
    suspend fun updateSource(fileKey: String, sourceUrl: String?, expectedBytes: Long?, md5: String?, format: String?) {
        dao.updateSource(fileKey, sourceUrl, expectedBytes, md5, format, clock())
    }

    // ---- garbage and recovery --------------------------------------------------------------------------------

    /**
     * Deletes the files nothing refers to: the row in a guarded statement, then the file. Returns how many rows.
     * A row being written (DOWNLOADING) is never touched; its commit deletes it when nobody wants it.
     */
    suspend fun collectGarbage(): Int = withContext(io) {
        fileLock.withLock {
            var n = 0
            for (row in dao.unreferencedFiles()) {
                if (dao.deleteIfUnreferenced(row.fileKey) > 0) {
                    n++
                    row.relPath?.let { deleteUnderOffline(it) }
                }
            }
            n
        }
    }

    /** Deletes every file in `offline/` that no DONE row names (a crash between a row delete and its file delete). */
    suspend fun sweepOrphans(): Int = withContext(io) {
        fileLock.withLock {
            val keep = dao.doneRelPaths().toSet()
            var n = 0
            offlineDir.listFiles()?.forEach { f ->
                if (f.isFile && "$DIR/${f.name}" !in keep && f.delete()) n++
            }
            n
        }
    }

    /** [recover] the first time only: the pass calls this at its start. */
    suspend fun ensureRecovered() {
        if (recovered) return
        recoverLock.withLock { if (!recovered) { recover(); recovered = true } }
    }

    /**
     * After a process death. A DOWNLOADING row with another run's claim goes back to PENDING, or is adopted as DONE
     * when its renamed file is there with the right size (and MD5, when the row has one). Every `.part` that is not
     * this run's is deleted.
     */
    suspend fun recover() = withContext(io) {
        fileLock.withLock {
            for (row in dao.foreignDownloading(runId)) {
                val adopted = adoptable(row)
                if (adopted != null) dao.adoptDone(row.fileKey, "$DIR/${adopted.name}", adopted.length(), clock())
                else dao.markPending(row.fileKey, clock())
            }
            tmpDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part") && claimOf(f.name) != runId) f.delete()
            }
        }
    }

    /** The file a dead process renamed but did not commit, when it is verifiably the whole thing. */
    private fun adoptable(row: OfflineFile): File? {
        if (!isValidKey(row.imageKey)) return null
        val size = row.expectedBytes ?: return null
        val prefix = "${row.imageKey}.orig."
        val candidates = offlineDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) } ?: return null
        return candidates.firstOrNull { f ->
            f.length() == size && (row.md5 == null || md5Hex(f).equals(row.md5, ignoreCase = true))
        }
    }

    private fun deleteUnderOffline(relPath: String) {
        val f = File(filesDir, relPath)
        val base = offlineDir.canonicalFile
        val canon = f.canonicalFile
        if (canon.parentFile == base) canon.delete()
    }

    /** A path under `filesDir` for a stored `relPath` (readers and tests). */
    fun resolve(relPath: String): File = File(filesDir, relPath)

    companion object {
        const val DIR = "offline"
        const val PENDING = "PENDING"
        const val DOWNLOADING = "DOWNLOADING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"

        /** Q4: keep at least this much free (1 GiB). */
        const val FREE_FLOOR_BYTES = 1L shl 30

        /** The size assumed for the room check when the listing gave none. */
        const val UNKNOWN_SIZE_BYTES = 8L shl 20

        val KEY_PATTERN = Regex("^[A-Za-z0-9]{4,16}$")

        fun isValidKey(imageKey: String): Boolean = KEY_PATTERN.matches(imageKey)

        fun fileKeyOf(imageKey: String) = "$imageKey/orig"

        fun relPathOf(imageKey: String, ext: String) = "$DIR/$imageKey.orig.$ext"

        private fun safeExt(ext: String): String = if (Regex("^[a-z0-9]{1,5}$").matches(ext)) ext else "jpg"

        /** `ext` from the response's `Content-Type`, else the URL's suffix, else `jpg`. */
        fun extensionFor(contentType: String?, url: String?): String {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            val fromType = when (type) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                "image/webp" -> "webp"
                "video/mp4" -> "mp4"
                "video/quicktime" -> "mov"
                else -> null
            }
            if (fromType != null) return fromType
            val suffix = url?.substringBefore('?')?.substringAfterLast('/', "")?.substringAfterLast('.', "")?.lowercase()
            return if (suffix != null && Regex("^[a-z0-9]{1,5}$").matches(suffix)) suffix else "jpg"
        }

        /** `{imageKey}.orig.{claim}.part` -> claim. */
        internal fun claimOf(partName: String): String =
            partName.removeSuffix(".part").substringAfterLast('.')

        fun md5Hex(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
