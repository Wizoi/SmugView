package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The offline tables (phase 5). 5-2 put rows in and read them back; 5-3 adds what `OfflineStore` needs (the
 * reference count, the state transitions, recovery); 5-4/5-5 add the pass's picker and the scheduler's questions.
 *
 * "Referenced" is never stored or counted by hand: it is a query over `collection_photos`, Image bookmarks and
 * `offline_gallery_items`, so it cannot drift (design 2.2).
 */
@Dao
interface OfflineDao {
    /** `OnConflictStrategy.IGNORE`: an existing row for the key wins, like the migration's `INSERT OR IGNORE`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFile(file: OfflineFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGallery(gallery: OfflineGallery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGalleryItems(items: List<OfflineGalleryItem>)

    @Query("SELECT * FROM offline_files WHERE fileKey = :fileKey")
    suspend fun getFile(fileKey: String): OfflineFile?

    @Query("SELECT * FROM offline_files ORDER BY fileKey")
    suspend fun allFiles(): List<OfflineFile>

    @Query("SELECT * FROM offline_galleries ORDER BY collectionId, albumKey")
    suspend fun allGalleries(): List<OfflineGallery>

    @Query("SELECT * FROM offline_gallery_items WHERE collectionId = :collectionId AND albumKey = :albumKey ORDER BY sortIndex")
    suspend fun itemsOf(collectionId: Long, albumKey: String): List<OfflineGalleryItem>

    // ---- reference count (a query, not a counter) ------------------------------------------------------------

    @Query(
        "SELECT (EXISTS(SELECT 1 FROM collection_photos WHERE imageKey = :imageKey) " +
            "OR EXISTS(SELECT 1 FROM collection_bookmarks WHERE type = 'Image' AND itemKey = :imageKey) " +
            "OR EXISTS(SELECT 1 FROM offline_gallery_items WHERE imageKey = :imageKey))"
    )
    suspend fun isReferenced(imageKey: String): Boolean

    /** Files nothing refers to any more and nobody is writing: the garbage collector's input. */
    @Query(
        "SELECT * FROM offline_files WHERE state != 'DOWNLOADING' " +
            "AND NOT EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = offline_files.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = offline_files.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = offline_files.imageKey) " +
            "ORDER BY fileKey"
    )
    suspend fun unreferencedFiles(): List<OfflineFile>

    /** One guarded delete: still unreferenced and still not being written, at the moment of the delete. */
    @Query(
        "DELETE FROM offline_files WHERE fileKey = :fileKey AND state != 'DOWNLOADING' " +
            "AND NOT EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = offline_files.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = offline_files.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = offline_files.imageKey)"
    )
    suspend fun deleteIfUnreferenced(fileKey: String): Int

    @Query("DELETE FROM offline_files WHERE fileKey = :fileKey")
    suspend fun deleteFile(fileKey: String): Int

    // ---- state transitions -----------------------------------------------------------------------------------

    /** Claim a row for this process's run, only if it is wanted and not already done or being written. */
    @Query(
        "UPDATE offline_files SET state = 'DOWNLOADING', claim = :claim, updatedAt = :now WHERE fileKey = :fileKey " +
            "AND (state = 'PENDING' OR (state = 'FAILED' AND retryable = 1)) AND (" +
            "EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = offline_files.imageKey) " +
            "OR EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = offline_files.imageKey) " +
            "OR EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = offline_files.imageKey))"
    )
    suspend fun claim(fileKey: String, claim: String, now: Long): Int

    /** DONE, only for the claim that wrote the file (a row deleted or re-claimed meanwhile is left alone). */
    @Query(
        "UPDATE offline_files SET state = 'DONE', failure = NULL, retryable = 0, nextAttemptAt = NULL, httpCode = NULL, " +
            "claim = NULL, relPath = :relPath, bytes = :bytes, updatedAt = :now WHERE fileKey = :fileKey AND claim = :claim"
    )
    suspend fun markDone(fileKey: String, claim: String, relPath: String, bytes: Long, now: Long): Int

    /** Recovery adopted a finished file: DONE regardless of claim. */
    @Query(
        "UPDATE offline_files SET state = 'DONE', failure = NULL, retryable = 0, nextAttemptAt = NULL, httpCode = NULL, " +
            "claim = NULL, relPath = :relPath, bytes = :bytes, updatedAt = :now WHERE fileKey = :fileKey"
    )
    suspend fun adoptDone(fileKey: String, relPath: String, bytes: Long, now: Long): Int

    /** Back to PENDING (a cancelled pass, a recovered row). Never touches a DONE row. */
    @Query("UPDATE offline_files SET state = 'PENDING', claim = NULL, updatedAt = :now WHERE fileKey = :fileKey AND state != 'DONE'")
    suspend fun markPending(fileKey: String, now: Long): Int

    /** FAILED with its reason; [attempts] is the new total. Never touches a DONE row. */
    @Query(
        "UPDATE offline_files SET state = 'FAILED', failure = :failure, retryable = :retryable, attempts = :attempts, " +
            "nextAttemptAt = :nextAttemptAt, httpCode = :httpCode, claim = NULL, updatedAt = :now " +
            "WHERE fileKey = :fileKey AND state != 'DONE'"
    )
    suspend fun markFailed(
        fileKey: String, failure: String, retryable: Boolean, attempts: Int, nextAttemptAt: Long?, httpCode: Int?, now: Long
    ): Int

    /** What a resolve (`image/{key}-0`) found. A null leaves the column as it was. */
    @Query(
        "UPDATE offline_files SET sourceUrl = :sourceUrl, expectedBytes = COALESCE(:expectedBytes, expectedBytes), " +
            "md5 = COALESCE(:md5, md5), format = COALESCE(:format, format), updatedAt = :now WHERE fileKey = :fileKey"
    )
    suspend fun updateSource(fileKey: String, sourceUrl: String?, expectedBytes: Long?, md5: String?, format: String?, now: Long): Int

    /** The user's "Try again": a permanent failure becomes PENDING with its count reset. */
    @Query(
        "UPDATE offline_files SET state = 'PENDING', failure = NULL, retryable = 0, attempts = 0, nextAttemptAt = NULL, " +
            "httpCode = NULL, claim = NULL, updatedAt = :now WHERE fileKey = :fileKey AND state = 'FAILED'"
    )
    suspend fun retryNow(fileKey: String, now: Long): Int

    // ---- recovery --------------------------------------------------------------------------------------------

    @Query("SELECT * FROM offline_files WHERE state = 'DOWNLOADING' AND (claim IS NULL OR claim != :runId)")
    suspend fun foreignDownloading(runId: String): List<OfflineFile>

    @Query("SELECT relPath FROM offline_files WHERE state = 'DONE' AND relPath IS NOT NULL")
    suspend fun doneRelPaths(): List<String>

    // ---- the pass's picker and the scheduler's questions -----------------------------------------------------

    /**
     * Rows a pass may try, oldest first: PENDING, or FAILED and retryable and due. A `wifiOnly` row waits for an
     * unmetered pass ([unmetered] true). [limit] lets the caller step over the rows it already tried this pass.
     */
    @Query(
        "SELECT * FROM offline_files WHERE (state = 'PENDING' OR (state = 'FAILED' AND retryable = 1 " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))) AND (wifiOnly = 0 OR :unmetered = 1) " +
            "ORDER BY createdAt, fileKey LIMIT :limit"
    )
    suspend fun candidates(now: Long, unmetered: Boolean, limit: Int): List<OfflineFile>

    /** Anything not DONE and not permanently failed: a reason to schedule a pass. */
    @Query("SELECT COUNT(*) FROM offline_files WHERE state IN ('PENDING', 'DOWNLOADING') OR (state = 'FAILED' AND retryable = 1)")
    suspend fun countWanted(): Int

    /** A `wifiOnly` row that is waiting: a reason to also enqueue the unmetered work. */
    @Query(
        "SELECT COUNT(*) FROM offline_files WHERE wifiOnly = 1 " +
            "AND (state IN ('PENDING', 'DOWNLOADING') OR (state = 'FAILED' AND retryable = 1))"
    )
    suspend fun countWantedWifiOnly(): Int

    /**
     * The earliest time a retryable failure is due, or null when there is none. A pass on mobile data
     * ([unmetered] false) does not count the `wifiOnly` rows it can never take, or it would reschedule itself forever.
     */
    @Query(
        "SELECT MIN(COALESCE(nextAttemptAt, 0)) FROM offline_files WHERE state = 'FAILED' AND retryable = 1 " +
            "AND (wifiOnly = 0 OR :unmetered = 1)"
    )
    suspend fun earliestRetryAt(unmetered: Boolean): Long?

    /** Rows a pass could take right now (PENDING, or a retryable failure already due). */
    @Query(
        "SELECT COUNT(*) FROM offline_files WHERE (state = 'PENDING' OR (state = 'FAILED' AND retryable = 1 " +
            "AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))) AND (wifiOnly = 0 OR :unmetered = 1)"
    )
    suspend fun countDue(now: Long, unmetered: Boolean): Int
}
