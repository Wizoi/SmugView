package com.smugview.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "cached_nodes",
    indices = [Index(value = ["parentNodeId"]), Index(value = ["nickname"])]
)
data class CachedNode(
    @PrimaryKey val nodeId: String,
    val parentNodeId: String?, // Root nodes will have null
    val type: String,          // "Folder" or "Album"
    val title: String,
    val description: String?,
    val access: String?,       // "Public", "Password", or "Inherited"
    val passwordHint: String?,
    val uri: String,
    val childNodesUri: String?,
    val albumUri: String?,
    val highlightImageUrl: String? = null,
    val childCount: Int? = null,
    val sortIndex: Int = 0,
    val webUri: String? = null,
    val dateModified: String? = null,
    @ColumnInfo(defaultValue = "") val nickname: String = ""  // Site nickname for cross-site isolation
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

/** A gallery's NodeID and its index `ImagesLastUpdated`: what "viewed" is written from (design 3.5). */
data class GalleryIlu(val nodeId: String, val imagesLastUpdated: String)

/**
 * Persisted flat index of ALL of a user's galleries (metadata only — no photos). Separate from
 * [CachedNode] because these are a flat list (not part of the browsable folder tree), used for
 * path/album-key resolution and search. Synced incrementally by `LastUpdated` so the app doesn't
 * re-crawl every album on every launch. Gallery *contents* are still loaded on demand.
 */
@Entity(tableName = "cached_albums", indices = [Index(value = ["nickname"])])
data class CachedAlbum(
    @PrimaryKey val albumKey: String,
    val nodeId: String,
    val name: String,
    val securityType: String?,
    val passwordHint: String?,
    val uri: String,
    val webUri: String?,
    val urlPath: String?,
    val imageCount: Int?,
    val dateModified: String?,       // SmugMug "LastUpdated"
    val galleryStyle: String?,
    val highlightImageUrl: String?,
    val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "") val nickname: String = "",
    /** Containing folder's nodeId, when known. Drives targeted [CachedNode] invalidation when a
     *  gallery is new or its LastUpdated changes, so the parent folder's cached child list is
     *  refetched instead of staying stale until a manual refresh. */
    val parentNodeId: String? = null,
    /** SmugMug `ImagesLastUpdated`: when the photos in the gallery last changed. Phase 2: the one
     *  "is it new?" date (design Q3). NULL until a sync fills it. */
    val imagesLastUpdated: String? = null
) {
    /** Maps to the flat [CachedNode] shape the rest of the app consumes via albumsCache. */
    fun toCachedNode(): CachedNode = CachedNode(
        nodeId = nodeId,
        parentNodeId = "root",
        type = "Album",
        title = name,
        description = null,
        access = securityType ?: "Public",
        passwordHint = passwordHint,
        uri = uri,
        childNodesUri = null,
        albumUri = uri,
        highlightImageUrl = highlightImageUrl,
        childCount = imageCount,
        sortIndex = sortIndex,
        webUri = webUri,
        dateModified = dateModified,
        nickname = nickname
    )
}

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

@Entity(
    tableName = "search_history",
    primaryKeys = ["query", "nickname"]
)
data class SearchHistory(
    val query: String,
    @ColumnInfo(defaultValue = "") val nickname: String = "",  // Site nickname — scopes history per site
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
