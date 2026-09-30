package com.smugview.app.ui

import android.net.Uri
import com.smugview.app.ui.navigation.CollectionRowKeys
import com.smugview.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RoutesAndKeysTest {

    // R-43: a gallery titled "Summer/2024" made "cast_controller/Summer/2024", which matches no
    // route (the pattern has one path segment), so casting crashed the nav graph.
    @Test
    fun castRoute_withSlashInTitle_isOneEncodedSegmentThatRoundTrips() {
        val title = "Summer/2024 & friends? 100%"
        val route = Routes.castController(title)
        val segments = Uri.parse("app://host/$route").pathSegments
        assertEquals(listOf("cast_controller", title), segments)
    }

    // R-42: a collection holding a gallery shortcut AND a photo from that gallery put the same
    // key into the LazyColumn twice (items(albums) + item(photo group)), which crashes Compose.
    @Test
    fun collectionRowKeys_areDistinctAcrossSectionsForTheSameId() {
        val id = "AbC123"
        val keys = listOf(
            CollectionRowKeys.folder(id),
            CollectionRowKeys.album(id),
            CollectionRowKeys.photoGroup(id)
        )
        assertTrue("keys collide: $keys", keys.toSet().size == keys.size)
        assertNotEquals(CollectionRowKeys.album(id), CollectionRowKeys.photoGroup(id))
    }
}
