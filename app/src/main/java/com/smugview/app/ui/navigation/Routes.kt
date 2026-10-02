package com.smugview.app.ui.navigation

import android.net.Uri

object Routes {
    fun castController(title: String): String = "cast_controller/${Uri.encode(title)}"

    /** The photo viewer's route, or null when a key is blank: "photo_detail//x" matches no route (R-44). */
    fun photoDetail(albumKey: String, imageKey: String): String? =
        if (albumKey.isBlank() || imageKey.isBlank()) null else "photo_detail/$albumKey/$imageKey"
}

object CollectionRowKeys {
    fun folder(itemKey: String): String = "folder:$itemKey"
    fun album(itemKey: String): String = "album:$itemKey"
    fun photoGroup(albumKey: String): String = "photos:$albumKey"
    fun savedPhoto(imageKey: String): String = "saved:$imageKey"
}
