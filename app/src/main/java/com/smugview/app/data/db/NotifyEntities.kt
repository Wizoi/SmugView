package com.smugview.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A site the owner follows for new-gallery notifications. `baselineDone` is false until the first complete listing is recorded. */
@Entity(tableName = "notify_sites")
data class NotifySite(
    @PrimaryKey val nickname: String,
    val enabled: Boolean,
    val baselineDone: Boolean
)

/** A gallery (NodeID, never AlbumKey) the owner has already been told about, or that existed when the site was followed. */
@Entity(tableName = "notify_known_albums", primaryKeys = ["nickname", "nodeId"])
data class NotifyKnownAlbum(
    val nickname: String,
    val nodeId: String
)
