package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * SELECT-only checks for the cache doctor (design phase-1-observability.md §2.5). Every method is a
 * single SELECT that returns one number; nothing here can write. A DAO is not part of the schema
 * identity hash, so adding it needs no Room version bump.
 */
@Dao
interface DoctorDao {
    @Query("SELECT COUNT(*) FROM cached_nodes")
    suspend fun countNodes(): Int

    @Query("SELECT COUNT(*) FROM cached_albums")
    suspend fun countIndexAlbums(): Int

    @Query("SELECT COUNT(*) FROM viewed_gallery_updates")
    suspend fun countViewedRows(): Int

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE nodeId = :nodeId")
    suspend fun countNodeById(nodeId: String): Int

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE parentNodeId = nodeId")
    suspend fun countSelfParent(): Int

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE instr(parentNodeId, '!') > 0")
    suspend fun countBangParent(): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE instr(parentNodeId, '!') > 0")
    suspend fun countBangParentIndex(): Int

    @Query(
        """SELECT COUNT(*) FROM cached_nodes a JOIN cached_nodes b ON a.parentNodeId = b.nodeId
           WHERE b.parentNodeId = a.nodeId AND a.nodeId < b.nodeId"""
    )
    suspend fun countTwoCycles(): Int

    /** Rows whose parent walk is still going after 32 hops. Bounded by `d < 33`, so it ends on cycles. */
    @Query(
        """WITH RECURSIVE w(s, cur, d) AS (
               SELECT nodeId, parentNodeId, 1 FROM cached_nodes WHERE parentNodeId IS NOT NULL
               UNION ALL
               SELECT w.s, n.parentNodeId, w.d + 1 FROM w JOIN cached_nodes n ON n.nodeId = w.cur
               WHERE w.d < 33 AND n.parentNodeId IS NOT NULL
           )
           SELECT COUNT(DISTINCT s) FROM w WHERE d >= 33"""
    )
    suspend fun countDeepChain(): Int

    @Query(
        """SELECT COUNT(*) FROM cached_nodes
           WHERE parentNodeId IS NOT NULL AND parentNodeId NOT IN ('root', 'search_result')
             AND parentNodeId <> nodeId AND instr(parentNodeId, '!') = 0
             AND parentNodeId NOT IN (SELECT nodeId FROM cached_nodes)"""
    )
    suspend fun countOrphanRows(): Int

    @Query(
        """SELECT COUNT(DISTINCT parentNodeId) FROM cached_nodes
           WHERE parentNodeId IS NOT NULL AND parentNodeId NOT IN ('root', 'search_result')
             AND parentNodeId <> nodeId AND instr(parentNodeId, '!') = 0
             AND parentNodeId NOT IN (SELECT nodeId FROM cached_nodes)"""
    )
    suspend fun countOrphanParents(): Int

    @Query(
        """SELECT COUNT(*) FROM cached_nodes
           WHERE type = 'Album' AND nodeId NOT LIKE 'virtual:%'
             AND nodeId NOT IN (SELECT nodeId FROM cached_albums)"""
    )
    suspend fun countAlbumNodeNotIndexed(): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE nodeId NOT IN (SELECT nodeId FROM cached_nodes)")
    suspend fun countIndexAlbumNoNode(): Int

    /**
     * Phase 2 (design 3.5): the dot seeds from the index alone, so a recent (ImagesLastUpdated within
     * 30 days) unviewed gallery is invisible to it only when no parent can be found for it, neither
     * the index's own nor a cached node's.
     */
    @Query(
        """SELECT COUNT(*) FROM cached_albums a
           LEFT JOIN cached_nodes n ON n.nodeId = a.nodeId
           LEFT JOIN viewed_gallery_updates v ON v.nodeId = a.nodeId
           WHERE a.imagesLastUpdated IS NOT NULL
             AND datetime(a.imagesLastUpdated) >= datetime('now', '-30 days')
             AND (v.lastViewedDateModified IS NULL
                  OR datetime(a.imagesLastUpdated) > datetime(v.lastViewedDateModified))
             AND COALESCE(a.parentNodeId, n.parentNodeId) IS NULL"""
    )
    suspend fun countRecentIndexInvisibleToDot(): Int

    /** Recent galleries in the anonymous index for one site (the sync report's `newInIndex30d`). */
    @Query(
        """SELECT COUNT(*) FROM cached_albums
           WHERE nickname = :nickname AND imagesLastUpdated IS NOT NULL
             AND datetime(imagesLastUpdated) >= datetime('now', '-30 days')"""
    )
    suspend fun countRecentIndexAlbums(nickname: String): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE nodeId = albumKey")
    suspend fun countIndexNodeIdIsAlbumKey(): Int

    @Query(
        """SELECT COUNT(*) FROM (SELECT nodeId FROM cached_albums GROUP BY nodeId HAVING COUNT(*) > 1)"""
    )
    suspend fun countDupIndexNodeId(): Int

    @Query(
        """SELECT COUNT(*) FROM (SELECT albumUri FROM cached_nodes WHERE albumUri IS NOT NULL
           GROUP BY albumUri HAVING COUNT(*) > 1)"""
    )
    suspend fun countDupAlbumUri(): Int

    @Query(
        """SELECT COUNT(*) FROM (SELECT nickname, urlPath FROM cached_albums WHERE urlPath IS NOT NULL
           GROUP BY nickname, urlPath HAVING COUNT(*) > 1)"""
    )
    suspend fun countDupIndexUrlPath(): Int

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE nickname = ''")
    suspend fun countEmptyNicknameNodes(): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE nickname = ''")
    suspend fun countEmptyNicknameIndex(): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE parentNodeId IS NOT NULL")
    suspend fun countIndexParentSet(): Int

    @Query("SELECT COUNT(*) FROM cached_albums WHERE dateModified IS NULL")
    suspend fun countIndexNullDate(): Int

    @Query(
        """SELECT COUNT(*) FROM cached_nodes
           WHERE type = 'Album' AND dateModified IS NOT NULL AND datetime(dateModified) IS NULL"""
    )
    suspend fun countUnparseableAlbumDate(): Int

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE parentNodeId = 'search_result'")
    suspend fun countSearchResultParented(): Int
}
