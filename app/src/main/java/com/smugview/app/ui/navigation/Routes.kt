package com.smugview.app.ui.navigation

import android.net.Uri

object Routes {
    fun castController(title: String): String = "cast_controller/${Uri.encode(title)}"
}

object CollectionRowKeys {
    fun folder(itemKey: String): String = "folder:$itemKey"
    fun album(itemKey: String): String = "album:$itemKey"
    fun photoGroup(albumKey: String): String = "photos:$albumKey"
}
