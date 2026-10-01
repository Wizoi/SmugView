package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /** Keeps a gallery: IGNORE, so asking again never resets a LISTED one (REPLACE would cascade-delete its items). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGallery(gallery: OfflineGallery): Long

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

    // ---- kept galleries (5-7) ---------------------------------------------------------------------------------

    @Query("SELECT * FROM offline_galleries WHERE collectionId = :collectionId AND albumKey = :albumKey")
    suspend fun getGallery(collectionId: Long, albumKey: String): OfflineGallery?

    @Query("DELETE FROM offline_gallery_items WHERE collectionId = :collectionId AND albumKey = :albumKey")
    suspend fun deleteGalleryItems(collectionId: Long, albumKey: String): Int

    /** The change date the album index holds now: what a listing is compared against later. */
    @Query("SELECT imagesLastUpdated FROM cached_albums WHERE albumKey = :albumKey")
    suspend fun cachedImagesLastUpdated(albumKey: String): String?

    /**
     * Galleries a pass may list, oldest first: never listed (LIST_PENDING), a retryable failure that is due
     * ([retryBefore] = now minus the retry gap, against `listedAt` = the time of the last try), or LISTED while the
     * album index says its photos changed since (`ImagesLastUpdated` newer than the one listed). A `wifiOnly`
     * gallery waits for an unmetered pass ([unmetered]).
     */
    @Query(
        "SELECT * FROM offline_galleries g WHERE (g.state = 'LIST_PENDING' " +
            "OR (g.state = 'FAILED' AND g.retryable = 1 AND (g.listedAt IS NULL OR g.listedAt <= :retryBefore)) " +
            "OR (g.state = 'LISTED' AND EXISTS(SELECT 1 FROM cached_albums a WHERE a.albumKey = g.albumKey " +
            "AND a.imagesLastUpdated IS NOT NULL AND a.imagesLastUpdated > COALESCE(g.listedIlu, '')))) " +
            "AND (g.wifiOnly = 0 OR :unmetered = 1) ORDER BY g.collectionId, g.albumKey LIMIT :limit"
    )
    suspend fun galleriesToList(retryBefore: Long, unmetered: Boolean, limit: Int): List<OfflineGallery>

    /** Galleries that want a listing at all (no due time, no network class): a reason to schedule a pass. */
    @Query(
        "SELECT COUNT(*) FROM offline_galleries g WHERE g.state = 'LIST_PENDING' " +
            "OR (g.state = 'FAILED' AND g.retryable = 1) " +
            "OR (g.state = 'LISTED' AND EXISTS(SELECT 1 FROM cached_albums a WHERE a.albumKey = g.albumKey " +
            "AND a.imagesLastUpdated IS NOT NULL AND a.imagesLastUpdated > COALESCE(g.listedIlu, '')))"
    )
    suspend fun countGalleriesWanted(): Int

    @Query(
        "SELECT COUNT(*) FROM offline_galleries g WHERE g.wifiOnly = 1 AND (g.state = 'LIST_PENDING' " +
            "OR (g.state = 'FAILED' AND g.retryable = 1) " +
            "OR (g.state = 'LISTED' AND EXISTS(SELECT 1 FROM cached_albums a WHERE a.albumKey = g.albumKey " +
            "AND a.imagesLastUpdated IS NOT NULL AND a.imagesLastUpdated > COALESCE(g.listedIlu, ''))))"
    )
    suspend fun countGalleriesWantedWifiOnly(): Int

    /** When a retryable gallery failure is next worth trying (`listedAt` + [gapMs]); null when there is none. */
    @Query(
        "SELECT MIN(COALESCE(listedAt, 0) + :gapMs) FROM offline_galleries WHERE state = 'FAILED' AND retryable = 1 " +
            "AND (wifiOnly = 0 OR :unmetered = 1)"
    )
    suspend fun earliestGalleryRetryAt(gapMs: Long, unmetered: Boolean): Long?

    @Query(
        "UPDATE offline_galleries SET state = 'LISTED', failure = NULL, retryable = 0, listedAt = :now, " +
            "listedIlu = :ilu, photoCount = :count WHERE collectionId = :collectionId AND albumKey = :albumKey"
    )
    suspend fun markGalleryListed(collectionId: Long, albumKey: String, now: Long, ilu: String?, count: Int): Int

    /** A failed try: the items of an earlier listing stay. `listedAt` becomes the time of this try (retry gap). */
    @Query(
        "UPDATE offline_galleries SET state = 'FAILED', failure = :failure, retryable = :retryable, listedAt = :now " +
            "WHERE collectionId = :collectionId AND albumKey = :albumKey"
    )
    suspend fun markGalleryFailed(collectionId: Long, albumKey: String, failure: String, retryable: Boolean, now: Long): Int

    /** A password came into session: a gallery that was permanently locked is worth listing again. */
    @Query(
        "UPDATE offline_galleries SET state = 'LIST_PENDING', failure = NULL, retryable = 0 " +
            "WHERE state = 'FAILED' AND failure = 'LOCKED' AND retryable = 0"
    )
    suspend fun requeueLockedGalleries(): Int

    @Query("UPDATE offline_galleries SET wifiOnly = :wifiOnly WHERE collectionId = :collectionId AND albumKey = :albumKey")
    suspend fun setGalleryWifiOnly(collectionId: Long, albumKey: String, wifiOnly: Boolean): Int

    /**
     * A file that is not DONE waits for Wi-Fi only when NOTHING that wants it allows mobile data: a saved photo, an
     * Image bookmark and a gallery with `wifiOnly = 0` each do (Q3). Recomputed for the files of one gallery
     * whenever its rule changes.
     */
    @Query(
        "UPDATE offline_files SET wifiOnly = CASE WHEN " +
            "EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = offline_files.imageKey) " +
            "OR EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = offline_files.imageKey) " +
            "OR EXISTS(SELECT 1 FROM offline_gallery_items i JOIN offline_galleries g " +
            "ON g.collectionId = i.collectionId AND g.albumKey = i.albumKey " +
            "WHERE i.imageKey = offline_files.imageKey AND g.wifiOnly = 0) THEN 0 ELSE 1 END, updatedAt = :now " +
            "WHERE state != 'DONE' AND EXISTS(SELECT 1 FROM offline_gallery_items x " +
            "WHERE x.collectionId = :collectionId AND x.albumKey = :albumKey AND x.imageKey = offline_files.imageKey)"
    )
    suspend fun recomputeWifiOnlyForGallery(collectionId: Long, albumKey: String, now: Long): Int

    /** A row that exists and is not DONE may start using mobile data (a photo saved alone, or a looser gallery). */
    @Query("UPDATE offline_files SET wifiOnly = 0, updatedAt = :now WHERE fileKey = :fileKey AND wifiOnly = 1 AND state != 'DONE'")
    suspend fun loosenWifiOnly(fileKey: String, now: Long): Int

    /** A fresh listing knows the source, size and MD5 better than a row made without them. */
    @Query(
        "UPDATE offline_files SET sourceUrl = COALESCE(:sourceUrl, sourceUrl), expectedBytes = COALESCE(:bytes, expectedBytes), " +
            "md5 = COALESCE(:md5, md5), updatedAt = :now WHERE fileKey = :fileKey AND state IN ('PENDING', 'FAILED')"
    )
    suspend fun fillSource(fileKey: String, sourceUrl: String?, bytes: Long?, md5: String?, now: Long): Int

    /**
     * The photo changed on SmugMug (a DONE row whose known MD5 differs from the listed one): back to PENDING with
     * the new source, size and MD5; the old file is replaced when the new one commits.
     */
    @Query(
        "UPDATE offline_files SET state = 'PENDING', failure = NULL, retryable = 0, attempts = 0, nextAttemptAt = NULL, " +
            "httpCode = NULL, claim = NULL, relPath = NULL, bytes = NULL, sourceUrl = COALESCE(:sourceUrl, sourceUrl), " +
            "expectedBytes = :bytes, md5 = :md5, updatedAt = :now " +
            "WHERE fileKey = :fileKey AND state = 'DONE' AND md5 IS NOT NULL AND md5 != :md5"
    )
    suspend fun resetChanged(fileKey: String, sourceUrl: String?, bytes: Long?, md5: String, now: Long): Int

    // ---- legacy repair (5-8, design 6.2, Q6) -----------------------------------------------------------------

    /** Rows the 5-2 backfill created from the old code (`legacyPath` set) and the repair has not settled yet. */
    @Query("SELECT * FROM offline_files WHERE legacyPath IS NOT NULL ORDER BY fileKey")
    suspend fun legacyFiles(): List<OfflineFile>

    /**
     * A verified legacy file became this row's file: DONE, with the path under `offline/` and no pending legacy
     * path. Only a row nobody is writing (PENDING or FAILED); returns 0 otherwise.
     */
    @Query(
        "UPDATE offline_files SET state = 'DONE', failure = NULL, retryable = 0, nextAttemptAt = NULL, httpCode = NULL, " +
            "claim = NULL, relPath = :relPath, bytes = :bytes, legacyPath = NULL, updatedAt = :now " +
            "WHERE fileKey = :fileKey AND state IN ('PENDING', 'FAILED')"
    )
    suspend fun adoptLegacy(fileKey: String, relPath: String, bytes: Long, now: Long): Int

    @Query("UPDATE offline_files SET legacyPath = NULL WHERE fileKey = :fileKey")
    suspend fun clearLegacyPath(fileKey: String): Int

    @Query("SELECT * FROM offline_galleries WHERE state = 'LEGACY' ORDER BY collectionId, albumKey")
    suspend fun legacyGalleries(): List<OfflineGallery>

    @Query(
        "UPDATE offline_galleries SET state = 'LIST_PENDING', failure = NULL, retryable = 0 " +
            "WHERE collectionId = :collectionId AND albumKey = :albumKey AND state = 'LEGACY'"
    )
    suspend fun promoteLegacyGallery(collectionId: Long, albumKey: String): Int

    @Query("DELETE FROM offline_galleries WHERE collectionId = :collectionId AND albumKey = :albumKey AND state = 'LEGACY'")
    suspend fun dropLegacyGallery(collectionId: Long, albumKey: String): Int

    /** Galleries still to be listed: until they are, a legacy file may be the one a listing adopts. */
    @Query("SELECT COUNT(*) FROM offline_galleries WHERE state IN ('LEGACY', 'LIST_PENDING') OR (state = 'FAILED' AND retryable = 1)")
    suspend fun countUnsettledGalleries(): Int

    // ---- readers (5-9): what the screens show ------------------------------------------------------------------

    /** Every DONE file as (imageKey, relPath): one Flow for the whole app, never one per photo (R-49). */
    @Query("SELECT imageKey AS imageKey, relPath AS relPath FROM offline_files WHERE state = 'DONE' AND relPath IS NOT NULL")
    fun doneFiles(): Flow<List<DoneFile>>

    /** The file rows of the photos and Image bookmarks that one collection holds (its rows' states). */
    @Query(
        "SELECT * FROM offline_files WHERE imageKey IN (" +
            "SELECT imageKey FROM collection_photos WHERE collectionId = :collectionId " +
            "UNION SELECT itemKey FROM collection_bookmarks WHERE collectionId = :collectionId AND type = 'Image')"
    )
    fun filesOfCollection(collectionId: Long): Flow<List<OfflineFile>>

    @Query("SELECT * FROM offline_galleries WHERE collectionId = :collectionId ORDER BY albumKey")
    fun galleriesOfCollection(collectionId: Long): Flow<List<OfflineGallery>>

    /** One line per (kept gallery, photo): what a gallery's summary is counted from. */
    @Query(
        "SELECT i.albumKey AS albumKey, f.state AS state, f.failure AS failure, f.retryable AS retryable, " +
            "f.bytes AS bytes, f.expectedBytes AS expectedBytes, f.httpCode AS httpCode " +
            "FROM offline_gallery_items i JOIN offline_files f ON f.imageKey = i.imageKey WHERE i.collectionId = :collectionId"
    )
    fun galleryFilesOfCollection(collectionId: Long): Flow<List<GalleryFileRow>>

    /** The saved (DONE) photos of a kept gallery in listing order, whichever collection kept it. */
    @Query(
        "SELECT f.* FROM offline_files f JOIN offline_gallery_items i ON i.imageKey = f.imageKey " +
            "WHERE i.albumKey = :albumKey AND f.state = 'DONE' AND f.relPath IS NOT NULL " +
            "GROUP BY f.imageKey ORDER BY MIN(i.collectionId), MIN(i.sortIndex)"
    )
    suspend fun savedPhotosOfGallery(albumKey: String): List<OfflineFile>

    @Query("SELECT * FROM offline_files WHERE imageKey = :imageKey AND state = 'DONE' AND relPath IS NOT NULL")
    suspend fun doneFileOf(imageKey: String): OfflineFile?

    /**
     * Q8: what deleting [collectionId] would remove: saved (DONE) files this collection refers to and nothing
     * else does (another collection's photo or Image bookmark, or another collection's kept gallery).
     */
    @Query(
        "SELECT COUNT(*) AS photos, COALESCE(SUM(f.bytes), 0) AS bytes FROM offline_files f WHERE f.state = 'DONE' " +
            "AND (EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = f.imageKey AND p.collectionId = :collectionId) " +
            "OR EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = f.imageKey AND b.collectionId = :collectionId) " +
            "OR EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = f.imageKey AND g.collectionId = :collectionId)) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = f.imageKey AND p.collectionId != :collectionId) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = f.imageKey AND b.collectionId != :collectionId) " +
            "AND NOT EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = f.imageKey AND g.collectionId != :collectionId)"
    )
    suspend fun savedOnlyIn(collectionId: Long): SavedStats

    /**
     * Q4 (6-11): what removing every bookmark of the gallery [albumKey] (`removeBookmarkGlobally`) would delete: saved (DONE) files
     * that gallery's kept-offline listing refers to, in any collection, and nothing else does (a photo saved by hand, an Image
     * bookmark, or another gallery's listing).
     */
    @Query(
        "SELECT COUNT(*) AS photos, COALESCE(SUM(f.bytes), 0) AS bytes FROM offline_files f WHERE f.state = 'DONE' " +
            "AND EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = f.imageKey AND g.albumKey = :albumKey) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_photos p WHERE p.imageKey = f.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM collection_bookmarks b WHERE b.type = 'Image' AND b.itemKey = f.imageKey) " +
            "AND NOT EXISTS(SELECT 1 FROM offline_gallery_items g WHERE g.imageKey = f.imageKey AND g.albumKey != :albumKey)"
    )
    suspend fun savedOnlyByGallery(albumKey: String): SavedStats
}

/** A saved file: the photo and where it is, relative to `filesDir`. */
data class DoneFile(val imageKey: String, val relPath: String)

/** One photo of a kept gallery, as the gallery's summary counts it. */
data class GalleryFileRow(
    val albumKey: String,
    val state: String,
    val failure: String?,
    val retryable: Boolean,
    val bytes: Long?,
    val expectedBytes: Long?,
    val httpCode: Int?
)

data class SavedStats(val photos: Int, val bytes: Long)
