package com.smugview.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** State of "have I told the owner about this gallery?". It is deliberately not `cached_albums` or the dot's tables. */
@Dao
interface NotifyDao {
    @Query("SELECT * FROM notify_sites WHERE nickname = :nickname")
    suspend fun site(nickname: String): NotifySite?

    @Query("SELECT COUNT(*) FROM notify_sites WHERE nickname = :nickname AND enabled = 1")
    fun followingCount(nickname: String): Flow<Int>

    @Query("SELECT * FROM notify_sites WHERE enabled = 1")
    suspend fun enabledSites(): List<NotifySite>

    @Query("SELECT nodeId FROM notify_known_albums WHERE nickname = :nickname")
    suspend fun knownIds(nickname: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSite(site: NotifySite)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addKnown(rows: List<NotifyKnownAlbum>)

    @Query("DELETE FROM notify_known_albums WHERE nickname = :nickname")
    suspend fun clearKnown(nickname: String)

    @Query("DELETE FROM notify_sites WHERE nickname = :nickname")
    suspend fun deleteSite(nickname: String)

    /** Following starts without a baseline, and without any gallery known, so a re-follow never announces what appeared while it was off. */
    @Transaction
    suspend fun follow(nickname: String) {
        clearKnown(nickname)
        putSite(NotifySite(nickname, enabled = true, baselineDone = false))
    }

    @Transaction
    suspend fun unfollow(nickname: String) {
        clearKnown(nickname)
        deleteSite(nickname)
    }

    @Transaction
    suspend fun recordBaseline(nickname: String, nodeIds: List<String>) {
        addKnown(nodeIds.map { NotifyKnownAlbum(nickname, it) })
        putSite(NotifySite(nickname, enabled = true, baselineDone = true))
    }
}
