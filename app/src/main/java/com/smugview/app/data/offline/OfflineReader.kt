package com.smugview.app.data.offline

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.OfflineFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * What the screens read about saved files (phase 5 design 2 and 5-9). Reads only; every write is
 * [OfflineCollections] or [OfflineStore]. The files under `filesDir/offline/` are still owned by the store, so
 * a path is only ever taken from a DONE row.
 *
 *  - [localFiles]: one Flow of "photo -> its saved file" for the whole app (not one per photo, R-49). The viewer
 *    prefers it, so a saved photo opens with no network (Q2).
 *  - [collection]: the states of one collection's rows and of its kept galleries, as one Flow.
 *  - [savedGallery]: the photos of a kept gallery that are on this phone, in listing order, for opening it offline.
 *  - [deleteConfirm]: what deleting a collection would remove (Q8), counted by the database.
 */
class OfflineReader(
    private val db: AppDatabase,
    private val store: OfflineStore,
    /** Whether the phone has a network now (any kind). Folded into the kept-gallery text so it names the real cause. */
    private val online: Flow<Boolean> = flowOf(true)
) {
    private val dao get() = db.offlineDao()

    /** A DONE row's file and its row: [row] is what the listing or the save knew about the photo. */
    class SavedPhoto(val row: OfflineFile, val file: File)

    /** The states a collection screen draws. Keys: image key for [rows], album key for [galleries]. */
    class CollectionOffline(
        val rows: Map<String, RowState>,
        val galleries: Map<String, GallerySummary>
    ) {
        companion object { val EMPTY = CollectionOffline(emptyMap(), emptyMap()) }
    }

    class DeleteConfirm(val photos: Int, val bytes: Long, val text: String)

    /** Every saved photo and where it is. One query for the whole app. */
    fun localFiles(): Flow<Map<String, File>> =
        dao.doneFiles().map { rows -> rows.associate { it.imageKey to store.resolve(it.relPath) } }

    /** The saved file of [imageKey], or null when it is not (completely) saved or the file is gone. */
    suspend fun localFile(imageKey: String): File? {
        val row = dao.doneFileOf(imageKey) ?: return null
        return store.resolve(row.relPath ?: return null).takeIf { it.isFile && it.length() > 0 }
    }

    /** The saved copy of [imageKey] with what its row knew (title, date, format), or null. */
    suspend fun savedPhoto(imageKey: String): SavedPhoto? {
        val row = dao.doneFileOf(imageKey) ?: return null
        val file = store.resolve(row.relPath ?: return null)
        return if (file.isFile && file.length() > 0) SavedPhoto(row, file) else null
    }

    fun collection(collectionId: Long): Flow<CollectionOffline> =
        combine(
            dao.filesOfCollection(collectionId),
            dao.galleriesOfCollection(collectionId),
            dao.galleryFilesOfCollection(collectionId),
            online.distinctUntilChanged()
        ) { files, galleries, galleryRows, connected ->
            val free = store.freeBytesNow()
            val rows = files.associate { it.imageKey to OfflineRowState.of(it, store.needBytes(it.expectedBytes), free) }
            val byGallery = galleryRows.groupBy { it.albumKey }
            val summaries = galleries.associate { g ->
                val own = byGallery[g.albumKey].orEmpty()
                val need = own.filter { it.state != OfflineStore.DONE }.sumOf { it.expectedBytes ?: store.needBytes(null) }
                g.albumKey to OfflineRowState.summary(g, own, need, free, connected)
            }
            CollectionOffline(rows, summaries)
        }

    /** The saved photos of the kept gallery [albumKey] whose files exist, in listing order; empty when none. */
    suspend fun savedGallery(albumKey: String): List<SavedPhoto> =
        dao.savedPhotosOfGallery(albumKey).mapNotNull { row ->
            val file = store.resolve(row.relPath ?: return@mapNotNull null)
            if (file.isFile && file.length() > 0) SavedPhoto(row, file) else null
        }

    /** Q8: null when deleting [collectionId] would remove no saved photo (no confirmation needed). */
    suspend fun deleteConfirm(collectionId: Long, name: String): DeleteConfirm? {
        val stats = dao.savedOnlyIn(collectionId)
        if (stats.photos == 0) return null
        return DeleteConfirm(stats.photos, stats.bytes, OfflineMessages.deleteConfirm(name, stats.photos, stats.bytes))
    }

    /** Q4 (6-11): null when removing every bookmark of the gallery [albumKey] would delete no saved photo. */
    suspend fun removeGalleryConfirm(albumKey: String, name: String): DeleteConfirm? {
        val stats = dao.savedOnlyByGallery(albumKey)
        if (stats.photos == 0) return null
        return DeleteConfirm(stats.photos, stats.bytes, OfflineMessages.deleteConfirm(name, stats.photos, stats.bytes))
    }
}
