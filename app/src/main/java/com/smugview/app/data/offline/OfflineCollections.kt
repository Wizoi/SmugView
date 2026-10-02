package com.smugview.app.data.offline

import androidx.room.withTransaction
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import kotlinx.coroutines.CancellationException

/**
 * Every write that adds or removes something a collection refers to, and the offline consequence of it (phase 5
 * design 2.6, step 5-6). A screen or controller never touches `collection_photos`, `collection_bookmarks` or the
 * offline tables for these; it calls here, so a reference and its file row are never written apart.
 *
 *  - An add is ONE transaction: the reference (`collection_photos` / Image bookmark) and an `offline_files`
 *    `INSERT OR IGNORE` through [OfflineStore.request]. A row that exists wins, so saving a photo again (or from two
 *    coroutines at once, or while it is being downloaded) never resets a DONE or DOWNLOADING file (N4). Then [kick].
 *  - A removal is ONE transaction that deletes the reference (and, for a gallery, its `offline_galleries` row and
 *    items; see `CollectionDao.removeBookmark`), with no network (R-41). Whether a file is still wanted is the
 *    reference-count QUERY, so a file shared by two collections, a saved photo and a kept gallery stays until the
 *    last of them goes (N5). Then [OfflineStore.collectGarbage] runs at once (space comes back immediately and
 *    offline), and the scheduler is kicked only if something is still wanted.
 *  - Nothing here fails the caller because WorkManager or the garbage collector did: the write is committed, and
 *    the next app start or kick picks the rest up.
 */
class OfflineCollections(
    private val db: AppDatabase,
    private val store: OfflineStore,
    private val scheduler: OfflineScheduler,
    private val settings: OfflineSettings
) {
    private val dao get() = db.collectionDao()

    /** Saves a photo to a collection (the Favorites path) and wants its file (Q3: any network). */
    suspend fun savePhoto(photo: CollectionPhoto, nickname: String) {
        db.withTransaction {
            // `collection_photos` is REPLACEd (same key, same collection); the file's row is not touched by it (N4).
            dao.addPhotoToCollection(photo)
            store.request(
                imageKey = photo.imageKey,
                albumKey = photo.albumKey.ifEmpty { null },
                nickname = nickname,
                sourceUrl = photo.archivedUri?.takeIf { it.isNotEmpty() },
                title = photo.title,
                thumbnailUrl = photo.thumbnailUrl,
                dateTaken = photo.dateTaken
            )
        }
        kick()
    }

    /**
     * Bookmarks something. An Image bookmark is a reference to the photo's file, so it wants the file ([sourceUrl]
     * is the original's URL when the caller has one, else null and the pass resolves it with `image/{key}-0`, N6).
     * A Folder or a gallery is a shortcut only (Q1 (a)): nothing is downloaded; "Keep offline" is a separate action.
     */
    suspend fun addBookmark(bookmark: CollectionBookmark, nickname: String, sourceUrl: String?) {
        val isImage = bookmark.type == TYPE_IMAGE
        db.withTransaction {
            dao.addBookmark(bookmark)
            if (isImage) {
                store.request(
                    imageKey = bookmark.itemKey,
                    albumKey = bookmark.albumKey.ifEmpty { null },
                    nickname = nickname,
                    sourceUrl = sourceUrl?.takeIf { it.isNotEmpty() },
                    title = bookmark.title,
                    thumbnailUrl = bookmark.thumbnailUrl
                )
            }
        }
        if (isImage) kick()
    }

    /**
     * "Keep offline" for a bookmarked gallery (Q1 (a)): the gallery is wanted LIST_PENDING; the global rule ([OfflineSettings]) says on which network. The
     * next pass lists it and downloads its photos. Asking again changes nothing (a LISTED gallery stays LISTED).
     */
    suspend fun keepGalleryOffline(collectionId: Long, albumKey: String, nickname: String, title: String?) {
        store.keepGallery(collectionId, albumKey, nickname, title)
        kick()
    }

    /**
     * Stops keeping a gallery: its row and items go in one transaction with no network (R-41), and what only it
     * referenced is collected at once. The bookmark stays (a shortcut). Nothing here touches a saved password (R-21).
     */
    suspend fun stopKeepingGalleryOffline(collectionId: Long, albumKey: String) {
        dao.deleteOfflineGalleryRowAndItems(collectionId, albumKey)
        afterRemoval()
    }

    /** What the screens show and the dialog edits. */
    val networkRule: kotlinx.coroutines.flow.StateFlow<OfflineNetworkRule> get() = settings.rule

    suspend fun galleryWaiting(): OfflineStore.GalleryWaiting = store.galleryWaiting()

    /** The one global rule for kept galleries (addendum 2.1). Always kicks: both directions need their chain at once. */
    suspend fun setGalleryNetwork(rule: OfflineNetworkRule) {
        settings.set(rule)
        kick()
    }

    suspend fun removePhoto(imageKey: String, collectionId: Long) {
        dao.removePhotoFromCollection(imageKey, collectionId)
        afterRemoval()
    }

    suspend fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        dao.removeBookmark(collectionId, type, itemKey)
        afterRemoval()
    }

    /** "Try again" on a failed row (5-9): the row is wanted again and the pass is kicked. */
    suspend fun tryAgain(imageKey: String) {
        if (store.retryNow(imageKey)) kick()
    }

    /** "Remove" on a row (5-9): the photo and the Image bookmark of it leave this collection; an unshared copy goes. */
    suspend fun removeFromCollection(imageKey: String, collectionId: Long) {
        dao.removePhotoFromCollection(imageKey, collectionId)
        dao.removeBookmark(collectionId, TYPE_IMAGE, imageKey)
        afterRemoval()
    }

    /** A gallery that is gone from SmugMug: every collection's bookmark of it, and its kept-offline rows. */
    suspend fun removeBookmarkGlobally(itemKey: String) {
        dao.removeBookmarkGlobally(itemKey)
        afterRemoval()
    }

    suspend fun deleteCollection(collectionId: Long) {
        dao.deleteCollection(collectionId)
        afterRemoval()
    }

    private suspend fun afterRemoval() {
        try {
            store.collectGarbage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The pass collects it too.
        }
        kickIfWanted()
    }

    private suspend fun kick() {
        try {
            scheduler.kick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The write is committed; the next kick or app start schedules the pass.
        }
    }

    private suspend fun kickIfWanted() {
        try {
            scheduler.kickIfWanted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    companion object {
        const val TYPE_IMAGE = "Image"
        const val TYPE_ALBUM = "Album"
    }
}
