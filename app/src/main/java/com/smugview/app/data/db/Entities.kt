package com.smugview.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_nodes",
    indices = [Index(value = ["parentNodeId"])]
)
data class CachedNode(
    @PrimaryKey val nodeId: String,
    val parentNodeId: String?, // Root nodes will have null
    val type: String,          // "Folder" or "Album"
    val title: String,
    val description: String?,
    val access: String?,       // "Public" or "Password"
    val passwordHint: String?,
    val uri: String,
    val childNodesUri: String?,
    val albumUri: String?,
    val highlightImageUrl: String? = null,
    val childCount: Int? = null,
    val sortIndex: Int = 0,
    val webUri: String? = null,
    val dateModified: String? = null
) {
    fun getAlbumKey(): String {
        return if (albumUri?.contains("/album/") == true) {
            albumUri.substringAfterLast("/").substringBefore("!")
        } else {
            nodeId
        }
    }
}

@Entity(tableName = "viewed_gallery_updates")
data class ViewedGalleryUpdate(
    @PrimaryKey val nodeId: String,
    val lastViewedDateModified: String
)

@Entity(tableName = "offline_collections")
data class OfflineCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val siteNickname: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "collection_photos",
    primaryKeys = ["imageKey", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["collectionId"])]
)
data class CollectionPhoto(
    val imageKey: String,
    val collectionId: Long,
    val albumKey: String,
    val title: String?,
    val thumbnailUrl: String?,
    val archivedUri: String?, // Remote source url
    val localFilePath: String?, // Local file path on disk (once downloaded)
    val dateTaken: String?,
    val keywords: String?,
    val isDownloaded: Boolean = false
)

@Entity(
    tableName = "collection_bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = OfflineCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["collectionId"])]
)
data class CollectionBookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val type: String,          // "Folder", "Album", or "Image"
    val itemKey: String,       // nodeId for Folders/Albums, imageKey for Images
    val title: String,
    val albumKey: String = "", // Parent album key (for images, so we can group them under the gallery)
    val albumTitle: String = "", // Parent album title (for images, to display the group name)
    val thumbnailUrl: String? = null,
    val extraData: String? = null // Any other serialized/extra metadata
)

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "search_results",
    primaryKeys = ["searchQuery", "searchScope", "itemKey", "itemType"]
)
data class SearchResult(
    val searchQuery: String,
    val searchScope: String,
    val itemKey: String,
    val itemType: String, // "Folder", "Album", "Photo"
    val title: String,
    val description: String?,
    val thumbnailUrl: String?,
    
    // Extra CachedNode fields (Folder/Album)
    val access: String? = null,
    val passwordHint: String? = null,
    val uri: String? = null,
    val childNodesUri: String? = null,
    val albumUri: String? = null,
    val webUri: String? = null,
    
    // Extra Photo fields (AlbumImageData)
    val archivedUri: String? = null,
    val date: String? = null,
    val format: String? = null,
    val videoUrl: String? = null,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    val fileName: String? = null,
    val originalSize: Long? = null,
    val keywords: String? = null,
    
    val sortIndex: Int = 0
)

fun SearchResult.toAlbumImageData(): com.smugview.app.data.api.AlbumImageData {
    return com.smugview.app.data.api.AlbumImageData(
        imageKey = this.itemKey,
        title = this.title,
        caption = this.description,
        thumbnailUrl = this.thumbnailUrl,
        archivedUri = this.archivedUri,
        date = this.date,
        dateTime = this.date,
        fileName = this.fileName,
        keywords = this.keywords,
        webUri = this.webUri,
        originalWidth = this.originalWidth,
        originalHeight = this.originalHeight,
        originalSize = this.originalSize,
        format = this.format,
        uris = this.albumUri?.let { com.smugview.app.data.api.AlbumImageUris(album = it, imageAlbum = it) },
        videoUrl = this.videoUrl
    )
}
