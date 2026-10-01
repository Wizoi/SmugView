package com.smugview.app.data.offline

import androidx.sqlite.db.SupportSQLiteDatabase
import com.smugview.app.data.repository.FakeOriginals
import java.time.Duration
import java.time.Instant

/**
 * The phase 5 "OfflineFixture" (design section 5): what a real phone's offline tables look like on
 * `idzifamily` today, written as raw SQL so the same rows go into a version 16 database (migration tests)
 * and into the current one (worker tests; the legacy columns exist in both).
 *
 *  - collection 1 "Favorites" and collection 2 "Trip"
 *  - [SHARED] is in both collections: one file, two rows (R-38)
 *  - [BROKEN] is a legacy failure: `isDownloaded = 1, localFilePath = ''` (R-40)
 *  - [PENDING] was added and never downloaded
 *  - an Image bookmark on [SHARED] and a gallery bookmark `FfHCms` (NodeID `LCdk7F`, AlbumKey != NodeID)
 *    in "Trip"; that gallery sits under the password folder `2sDN5x` -> `P4BKB` -> gallery
 *
 * Sources are real-shaped `ArchivedUri`s ([FakeOriginals]); dates are relative to now (findings #9).
 */
object OfflineFixture {
    const val SITE = "idzifamily"
    const val SHARED = "XVRvVTM"
    const val BROKEN = "n83tQ3s"
    const val PENDING = "Hk42gZp"
    const val GALLERY_ALBUM_KEY = "FfHCms"
    const val GALLERY_NODE_ID = "LCdk7F"

    fun legacyPath(key: String) = "offline_photos/$key.jpg"

    fun insert(db: SupportSQLiteDatabase, now: Instant = Instant.now()) {
        val taken = now.minus(Duration.ofDays(10)).toString()
        fun photo(key: String, collection: Int, album: String, downloaded: Int, path: String?) {
            val p = if (path == null) "NULL" else "'$path'"
            db.execSQL(
                "INSERT INTO collection_photos (imageKey, collectionId, albumKey, title, thumbnailUrl, archivedUri, " +
                    "localFilePath, dateTaken, keywords, isDownloaded) VALUES ('$key', $collection, '$album', 'IMG_$key', " +
                    "'https://photos.smugmug.com/photos/i-$key/0/Th/i-$key-Th.jpg', '${FakeOriginals.archivedUri(key)}', " +
                    "$p, '$taken', NULL, $downloaded)"
            )
        }
        db.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (1, 'Favorites', '$SITE', ${now.toEpochMilli()})")
        db.execSQL("INSERT INTO offline_collections (id, name, siteNickname, createdAt) VALUES (2, 'Trip', '$SITE', ${now.toEpochMilli()})")
        photo(SHARED, 1, GALLERY_ALBUM_KEY, 0, null)
        photo(SHARED, 2, GALLERY_ALBUM_KEY, 0, null)
        photo(BROKEN, 1, "N74KSK", 1, "")
        photo(PENDING, 2, GALLERY_ALBUM_KEY, 0, null)
        db.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                "VALUES (2, 'Image', '$SHARED', 'IMG_$SHARED', '$GALLERY_ALBUM_KEY', 'Class photos', NULL)"
        )
        db.execSQL(
            "INSERT INTO collection_bookmarks (collectionId, type, itemKey, title, albumKey, albumTitle, thumbnailUrl) " +
                "VALUES (2, 'Album', '$GALLERY_ALBUM_KEY', 'Class photos', '$GALLERY_ALBUM_KEY', 'Class photos', NULL)"
        )
    }
}
