package com.smugview.app.ui.viewmodel

import android.content.SharedPreferences
import com.smugview.app.data.api.AlbumImageData
import com.smugview.app.data.api.ImageSearchPayload
import com.smugview.app.data.api.ImageSearchResponse
import com.smugview.app.data.api.PagesData
import com.smugview.app.data.api.TopKeywordsPayload
import com.smugview.app.data.api.TopKeywordsResponse
import com.smugview.app.data.api.UserTopKeywordsContainer
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.scenario.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * R-19: coming back to the Tags tab with the same scope must not empty the keyword results while the
 * chips stay selected. The controller runs on real Unconfined scopes; the repository is a mock that
 * serves one page of two photos for the keyword and a keyword cloud for the scan.
 */
class TagSearchReentryTest {
    private val root = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repo = mock(SmugMugRepository::class.java)
    private val prefs = mock(SharedPreferences::class.java)
    private val scopeFlow = MutableStateFlow(FAMILY)
    private val photos = listOf(AlbumImageData(imageKey = "i1", title = "one"), AlbumImageData(imageKey = "i2", title = "two"))
    private lateinit var controller: TagSearchController
    private val sizes = java.util.concurrent.CopyOnWriteArrayList<Int>()

    @Before fun setUp() {
        runBlocking {
            `when`(repo.getUserTopKeywords(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(TopKeywordsResponse(TopKeywordsPayload(UserTopKeywordsContainer(listOf("sunset", "dog")))))
            `when`(repo.getImagesByKeywordPage(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(ImageSearchResponse(ImageSearchPayload(photos, PagesData(1, photos.size, photos.size))))
        }
        controller = TagSearchController(
            repository = repo, apiKey = "k", viewModelScope = root, siteScope = { root }, sharedPrefs = prefs,
            searchScope = scopeFlow, isViewingDetail = MutableStateFlow(false),
            activeNickname = MutableStateFlow("idzifamily"), getUnlockedPassword = { null }
        )
        root.launch { controller.allScopePhotos.collect { sizes += it.size } }
    }

    @After fun tearDown() { root.coroutineContext[Job]?.cancel() }

    private fun scanned() = !controller.isScanningTags.value && controller.scanProgress.value.startsWith("Scan complete")

    /** Open the Tags tab on [FAMILY], pick a tag and wait for its two photos. */
    private fun firstVisit() {
        controller.triggerTagScopeScan(FAMILY, clearSelected = true)
        awaitUntil("first scan done") { scanned() }
        controller.selectTag("sunset")
        awaitUntil("photos loaded") { controller.allScopePhotos.value.size == 2 }
    }

    @Test fun reEnteringTheTabWithTheSameScopeKeepsTheResultsAndTheChips() {
        firstVisit()
        controller.cancelTagSearchJob() // what leaving the tab does
        sizes.clear()

        controller.triggerTagScopeScan(FAMILY, clearSelected = false) // what entering it does, scope unchanged
        awaitUntil("second scan done") { scanned() }

        assertEquals(listOf("i1", "i2"), controller.allScopePhotos.value.map { it.imageKey })
        assertEquals(setOf("sunset"), controller.selectedTags.value.keys)
        assertTrue("results were never emptied while re-scanning, saw sizes $sizes", sizes.none { it == 0 })
    }

    @Test fun aDifferentScopeStillStartsEmpty() {
        firstVisit()
        controller.cancelTagSearchJob()

        controller.triggerTagScopeScan(SCHOOL, clearSelected = false)

        assertEquals(0, controller.allScopePhotos.value.size)
    }

    @Test fun anExplicitRescanWithTheSelectionClearedStillEmptiesTheResults() {
        firstVisit()

        controller.triggerTagScopeScan(FAMILY, clearSelected = true)
        awaitUntil("rescan done") { scanned() }

        assertEquals(emptySet<String>(), controller.selectedTags.value.keys)
        assertEquals(0, controller.allScopePhotos.value.size)
    }

    private companion object {
        val FAMILY = SearchScope("Folder: Family", "2sDN5x", "/api/v2/node/2sDN5x")
        val SCHOOL = SearchScope("Folder: School", "P4BKB", "/api/v2/node/P4BKB")
    }
}
