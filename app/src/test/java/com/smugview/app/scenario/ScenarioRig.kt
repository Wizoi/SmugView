package com.smugview.app.scenario

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.CollectionDao
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.repository.FakeSmugMugServer
import com.smugview.app.data.repository.InMemorySyncStateStore
import com.smugview.app.data.repository.RecordingSyncReporter
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.data.security.FakePasswordStore
import com.smugview.app.ui.viewmodel.SmugViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito

/**
 * Phase 3 scenario rig (design section 5): the real repository over a real in-memory Room database
 * and the fake server, and a real [SmugViewModel] on the Robolectric application's prefs. Ordering is
 * forced with [FakeSmugMugServer.hold] gates, not virtual time: OkHttp threads and the repository's
 * `Dispatchers.IO` are outside any test scheduler. Main is one real thread, so assertions poll with
 * [awaitUntil]. Call [close] from `@After`.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class ScenarioRig {
    val server = FakeSmugMugServer()
    val db: AppDatabase = TestDb.inMemory()
    val dao: CollectionDao = db.collectionDao()
    val reporter = RecordingSyncReporter()
    val passwords = FakePasswordStore()
    val syncState = InMemorySyncStateStore()
    val savedState = SavedStateHandle()
    val app: Application = ApplicationProvider.getApplicationContext()
    private val mainThread = newSingleThreadContext("main")

    val repository: SmugMugRepository
    val viewModel: SmugViewModel

    init {
        Dispatchers.setMain(mainThread)
        app.getSharedPreferences("smugview_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmugMugRepository(server.api(), dao, passwords, app, reporter, syncState).also {
            it.treeSyncDelayMs = 0
            it.unlockDelayMs = 0
        }
        val cast = Mockito.mock(CastManager::class.java)
        Mockito.`when`(cast.discoveredDevices).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(cast.activeDevice).thenReturn(MutableStateFlow(null))
        Mockito.`when`(cast.isCasting).thenReturn(MutableStateFlow(false))
        Mockito.`when`(cast.currentImageUri).thenReturn(MutableStateFlow(null))
        Mockito.`when`(cast.slideshowInterval).thenReturn(MutableStateFlow(5))
        Mockito.`when`(cast.isSlideshowPlaying).thenReturn(MutableStateFlow(false))
        viewModel = SmugViewModel(
            app, repository, Mockito.mock(WorkManager::class.java), cast, passwords,
            Dispatchers.Default, reporter, savedState
        )
    }

    fun close() {
        server.releaseAllGates()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        mainThread.close()
        db.close()
    }
}

/** Polls [condition] on the calling thread until it holds or [timeoutMs] passes; fails with [message]. */
fun awaitUntil(message: String = "condition", timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    if (!condition()) throw AssertionError("timed out after ${timeoutMs}ms waiting for: $message")
}
