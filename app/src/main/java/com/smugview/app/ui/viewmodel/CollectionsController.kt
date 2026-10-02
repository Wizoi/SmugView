package com.smugview.app.ui.viewmodel

import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.offline.OfflineCollections
import com.smugview.app.data.repository.SmugMugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns saved-content operations: local collections and bookmarks. Extracted from [SmugViewModel] as part of the
 * facade decomposition; the ViewModel keeps its public surface and delegates here.
 *
 * Phase 5 (step 5-6): every write that adds or removes a reference to a photo goes through [OfflineCollections],
 * the single owner of "this photo must have a file on the phone" and of deleting that file when nothing refers to it.
 * This controller no longer downloads, deletes or names a file, and it has no download progress of its own: the
 * old viewModelScope downloads (N7), the shared `offline_photos/{key}.jpg` (R-38) and the unconstrained workers
 * (R-39) are gone. The accessor lambdas expose the small pieces of ViewModel state these operations still need.
 */
class CollectionsController(
    private val repository: SmugMugRepository,
    private val offline: OfflineCollections,
    private val scope: CoroutineScope,
    private val getActiveNickname: () -> String?,
    private val getCurrentAlbumKey: () -> String
) {
    fun createCollection(name: String) {
        scope.launch {
            val nickname = getActiveNickname() ?: ""
            repository.createLocalCollection(name, nickname)
        }
    }

    /** Deletes the collection, its references and, once nothing else refers to them, the files (no network, R-41). */
    fun deleteCollection(collectionId: Long) {
        scope.launch {
            offline.deleteCollection(collectionId)
        }
    }

    fun renameCollection(collectionId: Long, newName: String) {
        scope.launch {
            repository.renameLocalCollection(collectionId, newName)
        }
    }

    fun getBookmarksForCollection(collectionId: Long): Flow<List<CollectionBookmark>> {
        return repository.getBookmarksForCollection(collectionId)
    }

    /**
     * Bookmarks an item. An Image bookmark also wants the photo's file (any network, Q3); a gallery or a folder is a
     * shortcut only (Q1 (a)) and downloads nothing. [imageUrl] is the original's URL when the caller has it: the
     * viewers pass `archivedUri ?: thumbnailUrl`, so a URL equal to the thumbnail is the fallback, not the original,
     * and is not kept (the pass resolves the real source with `image/{key}-0` instead).
     */
    fun addBookmark(collectionId: Long, type: String, itemKey: String, title: String, albumKey: String = "", albumTitle: String = "", thumbnailUrl: String? = null, imageUrl: String? = null) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "addBookmark: collectionId=$collectionId, type=$type, itemKey=$itemKey, title='$title'")
        }
        scope.launch {
            val bookmark = CollectionBookmark(
                collectionId = collectionId,
                type = type,
                itemKey = itemKey,
                title = title,
                albumKey = albumKey,
                albumTitle = albumTitle,
                thumbnailUrl = thumbnailUrl
            )
            val source = imageUrl?.takeIf { it.isNotEmpty() && it != thumbnailUrl }
            offline.addBookmark(bookmark, nickname = getActiveNickname() ?: "", sourceUrl = source)
        }
    }

    /** Unbookmarks; what only this bookmark kept on the phone is deleted, what another reference needs stays (N5). */
    fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "removeBookmark: collectionId=$collectionId, type=$type, itemKey=$itemKey")
        }
        scope.launch {
            offline.removeBookmark(collectionId, type, itemKey)
        }
    }

    /** A bookmark of a gallery or folder that SmugMug no longer has: gone from every collection. */
    fun removeBookmarkGlobally(itemKey: String) {
        scope.launch {
            offline.removeBookmarkGlobally(itemKey)
        }
    }

    /** "Keep offline" for a bookmarked gallery: it is saved on the network the global rule ([setGalleryNetwork]) allows. */
    fun keepGalleryOffline(collectionId: Long, albumKey: String, title: String?) {
        scope.launch {
            offline.keepGalleryOffline(collectionId, albumKey, getActiveNickname() ?: "", title)
        }
    }

    fun stopKeepingGalleryOffline(collectionId: Long, albumKey: String) {
        scope.launch {
            offline.stopKeepingGalleryOffline(collectionId, albumKey)
        }
    }

    val offlineNetworkRule: kotlinx.coroutines.flow.StateFlow<com.smugview.app.data.offline.OfflineNetworkRule> = offline.networkRule

    fun setOfflineNetworkRule(rule: com.smugview.app.data.offline.OfflineNetworkRule) {
        scope.launch {
            offline.setGalleryNetwork(rule)
        }
    }

    suspend fun isBookmarked(collectionId: Long, type: String, itemKey: String): Boolean {
        return repository.isBookmarked(collectionId, type, itemKey)
    }

    suspend fun isBookmarkedAnywhere(type: String, itemKey: String): Boolean {
        return repository.isBookmarkedAnywhere(type, itemKey)
    }

    fun addPhotoToCollection(photo: AlbumImageData, collectionId: Long) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "addPhotoToCollection: imageKey=${photo.imageKey}, collectionId=$collectionId")
        }
        scope.launch {
            val dbPhoto = CollectionPhoto(
                imageKey = photo.imageKey,
                collectionId = collectionId,
                albumKey = getCurrentAlbumKey(),
                title = photo.title ?: photo.caption,
                thumbnailUrl = photo.thumbnailUrl,
                archivedUri = photo.archivedUri,
                localFilePath = null,
                dateTaken = photo.date,
                keywords = photo.keywordsString,
                isDownloaded = false
            )
            offline.savePhoto(dbPhoto, nickname = getActiveNickname() ?: "")
        }
    }

    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> {
        return repository.getPhotosInCollection(collectionId)
    }

    fun removePhotoFromCollection(imageKey: String, collectionId: Long) {
        scope.launch {
            offline.removePhoto(imageKey, collectionId)
        }
    }
}
