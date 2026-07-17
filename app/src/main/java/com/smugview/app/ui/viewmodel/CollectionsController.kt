package com.smugview.app.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smugview.app.BuildConfig
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.db.CollectionBookmark
import com.smugview.app.data.db.CollectionPhoto
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.worker.OfflineDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Owns saved-content operations: local collections, bookmarks, and offline
 * download/delete of photos and albums. Extracted verbatim from [SmugViewModel]
 * as part of the facade decomposition — the ViewModel keeps its public surface
 * and delegates here, so screens and tests are unaffected.
 *
 * The shared [backgroundLoadingStatus] / [isBackgroundLoading] flows are passed
 * in (rather than owned here) because album loading also drives them; the
 * accessor lambdas expose the small pieces of ViewModel state these operations
 * still need (active site nickname, current album key, gallery passwords).
 */
class CollectionsController(
    private val application: Application,
    private val repository: SmugMugRepository,
    private val workManager: WorkManager,
    private val sharedPrefs: SharedPreferences,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val backgroundLoadingStatus: MutableStateFlow<String?>,
    private val isBackgroundLoading: MutableStateFlow<Boolean>,
    private val getActiveNickname: () -> String?,
    private val getCurrentAlbumKey: () -> String,
    private val getUnlockedPassword: suspend (String) -> String?
) {
    fun createCollection(name: String) {
        scope.launch {
            val nickname = getActiveNickname() ?: ""
            repository.createLocalCollection(name, nickname)
        }
    }

    fun deleteCollection(collectionId: Long) {
        scope.launch {
            repository.deleteLocalCollection(collectionId)
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
            repository.addBookmark(bookmark)

            // Automatically mark as offline (download)
            if (type == "Image" && !imageUrl.isNullOrEmpty()) {
                downloadPhotoOffline(itemKey, imageUrl)
            } else if (type == "Album") {
                downloadAlbumOffline(itemKey, apiKey, getUnlockedPassword(itemKey))
            }
        }
    }

    fun removeBookmark(collectionId: Long, type: String, itemKey: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SmugViewModel", "removeBookmark: collectionId=$collectionId, type=$type, itemKey=$itemKey")
        }
        scope.launch {
            repository.removeBookmark(collectionId, type, itemKey)

            // If not bookmarked anywhere else, clear the offline file
            val isBookmarkedAnywhere = repository.isBookmarkedAnywhere(type, itemKey)
            if (!isBookmarkedAnywhere) {
                if (type == "Image") {
                    deleteOfflinePhoto(itemKey)
                } else if (type == "Album") {
                    deleteOfflineAlbum(itemKey, apiKey)
                }
            }
        }
    }

    fun downloadPhotoOffline(imageKey: String, imageUrl: String) {
        downloadPhotoOffline(imageKey, imageUrl, {}, {})
    }

    fun deleteOfflinePhoto(imageKey: String) {
        deleteOfflinePhoto(imageKey, {})
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
            repository.addPhotoToCollection(dbPhoto)

            val syncRequest = OneTimeWorkRequestBuilder<OfflineDownloadWorker>().build()
            workManager.enqueue(syncRequest)
        }
    }

    fun getPhotosInCollection(collectionId: Long): Flow<List<CollectionPhoto>> {
        return repository.getPhotosInCollection(collectionId)
    }

    fun isAlbumDownloaded(albumKey: String): Boolean {
        return sharedPrefs.getBoolean("offline_album_$albumKey", false)
    }

    fun downloadAlbumOffline(albumKey: String, apiKey: String, password: String? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                backgroundLoadingStatus.value = "Starting album download..."
                isBackgroundLoading.value = true
                val photos = repository.getAllAlbumImages(albumKey, apiKey, password)
                if (photos.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(application, "No photos to download", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val directory = File(application.filesDir, "offline_photos")
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val client = okhttp3.OkHttpClient()
                var successCount = 0
                photos.forEachIndexed { index, photo ->
                    backgroundLoadingStatus.value = "Downloading ${index + 1}/${photos.size}..."
                    val url = photo.archivedUri ?: photo.thumbnailUrl
                    if (!url.isNullOrEmpty()) {
                        try {
                            val request = okhttp3.Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val body = response.body
                                if (body != null) {
                                    val file = File(directory, "${photo.imageKey}.jpg")
                                    body.byteStream().use { input ->
                                        FileOutputStream(file).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    repository.updateDownloadStatusForAll(photo.imageKey, file.absolutePath, true)
                                    successCount++
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                sharedPrefs.edit().putBoolean("offline_album_$albumKey", true).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Album downloaded offline ($successCount photos)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Album download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                backgroundLoadingStatus.value = null
                isBackgroundLoading.value = false
            }
        }
    }

    fun deleteOfflineAlbum(albumKey: String, apiKey: String, password: String? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                backgroundLoadingStatus.value = "Deleting offline files..."
                isBackgroundLoading.value = true
                val photos = repository.getAllAlbumImages(albumKey, apiKey, password)
                val directory = File(application.filesDir, "offline_photos")
                photos.forEach { photo ->
                    val file = File(directory, "${photo.imageKey}.jpg")
                    if (file.exists()) {
                        file.delete()
                    }
                    repository.updateDownloadStatusForAll(photo.imageKey, null, false)
                }
                sharedPrefs.edit().remove("offline_album_$albumKey").apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Offline files deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                backgroundLoadingStatus.value = null
                isBackgroundLoading.value = false
            }
        }
    }

    fun downloadPhotoOffline(imageKey: String, imageUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(imageUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onFailure("Download failed: HTTP ${response.code}") }
                    return@launch
                }
                val body = response.body
                if (body == null) {
                    withContext(Dispatchers.Main) { onFailure("Empty response body") }
                    return@launch
                }
                val directory = File(application.filesDir, "offline_photos")
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val file = File(directory, "$imageKey.jpg")
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                repository.updateDownloadStatusForAll(imageKey, file.absolutePath, true)
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFailure(e.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    fun deleteOfflinePhoto(imageKey: String, onSuccess: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val directory = File(application.filesDir, "offline_photos")
                val file = File(directory, "$imageKey.jpg")
                if (file.exists()) {
                    file.delete()
                }
                repository.updateDownloadStatusForAll(imageKey, null, false)
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun removePhotoFromCollection(imageKey: String, collectionId: Long) {
        scope.launch {
            repository.removePhotoFromCollection(imageKey, collectionId)
        }
    }
}
