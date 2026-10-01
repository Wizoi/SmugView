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
import com.smugview.app.data.repository.LoopbackSmugMug
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
class ScenarioRig(retrying: Boolean = false, httpCache: Boolean = false) {
    val server = FakeSmugMugServer()
    private val cacheDir: java.io.File? = if (httpCache) java.nio.file.Files.createTempDirectory("smugview-http-cache").toFile() else null

    /**
     * Phase 4: with [httpCache] the repository talks to the fake over a real loopback socket through the
     * production client ([com.smugview.app.di.buildSmugMugClient]: real OkHttp cache, cache rewrite,
     * cookie jar, offline fallback). Null for the default in-process interceptor rig.
     */
    val loopback: LoopbackSmugMug? = cacheDir?.let { LoopbackSmugMug(server, it) }
    val db: AppDatabase = TestDb.inMemory()
    val dao: CollectionDao = db.collectionDao()
    val reporter = RecordingSyncReporter()
    val passwords = FakePasswordStore()
    val syncState = InMemorySyncStateStore()
    val savedState = SavedStateHandle()
    val app: Application = ApplicationProvider.getApplicationContext()
    private val mainThread = newSingleThreadContext("main")

    /** The offline writers over this rig's database; the scheduler is a mock WorkManager (no pass runs here). */
    val offlineFilesDir: java.io.File = java.nio.file.Files.createTempDirectory("smugview-rig-files").toFile()
    val offlineStore = com.smugview.app.data.offline.OfflineStore(db, offlineFilesDir, freeBytes = { 50L shl 30 })
    val reader = com.smugview.app.data.offline.OfflineReader(db, offlineStore)
    private fun offline() = com.smugview.app.data.offline.OfflineCollections(
        db, offlineStore,
        com.smugview.app.data.offline.OfflineScheduler(workManager = { Mockito.mock(WorkManager::class.java) }, store = offlineStore)
    )

    val repository: SmugMugRepository
    val viewModel: SmugViewModel

    init {
        Dispatchers.setMain(mainThread)
        app.getSharedPreferences("smugview_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        repository = newRepository(retrying)
        viewModel = newViewModel(repository, savedState)
    }

    private fun newRepository(retrying: Boolean) =
        SmugMugRepository(loopback?.api(retrying = retrying) ?: server.api(retrying), dao, passwords, app, reporter, syncState).also {
            it.treeSyncDelayMs = 0
            it.unlockDelayMs = 0
        }

    private fun newViewModel(repo: SmugMugRepository, handle: SavedStateHandle): SmugViewModel {
        val cast = Mockito.mock(CastManager::class.java)
        Mockito.`when`(cast.discoveredDevices).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(cast.activeDevice).thenReturn(MutableStateFlow(null))
        Mockito.`when`(cast.isCasting).thenReturn(MutableStateFlow(false))
        Mockito.`when`(cast.currentImageUri).thenReturn(MutableStateFlow(null))
        Mockito.`when`(cast.slideshowInterval).thenReturn(MutableStateFlow(5))
        Mockito.`when`(cast.isSlideshowPlaying).thenReturn(MutableStateFlow(false))
        return SmugViewModel(
            app, repo, offline(), reader, cast, passwords,
            Dispatchers.Default, reporter, handle
        )
    }

    private val restarted = java.util.concurrent.CopyOnWriteArrayList<SmugViewModel>()

    /** The repository of the latest [restartProcess] (null before one). */
    @Volatile var restartedRepository: SmugMugRepository? = null
        private set

    /**
     * Process death: the running view model's work stops, and a NEW repository and view model start
     * over the same database, preferences, saved passwords and fake server, with [handle] as their
     * saved state (what the system hands back from the bundle). Nothing in memory survives.
     */
    fun restartProcess(handle: SavedStateHandle): SmugViewModel {
        viewModel.viewModelScope.cancel()
        restarted.forEach { it.viewModelScope.cancel() }
        val repo = newRepository(false)
        restartedRepository = repo
        return newViewModel(repo, handle).also { restarted += it }
    }

    fun close() {
        server.releaseAllGates()
        viewModel.viewModelScope.cancel()
        restarted.forEach { it.viewModelScope.cancel() }
        resetMainWhenIdle()
        mainThread.close()
        db.close()
        loopback?.close()
        cacheDir?.deleteRecursively()
        offlineFilesDir.deleteRecursively()
    }
}

/** A released request's callback can read Dispatchers.Main while we reset it; resetMain then throws, so retry. */
private fun resetMainWhenIdle() {
    repeat(50) {
        try { Dispatchers.resetMain(); return } catch (e: IllegalStateException) { Thread.sleep(20) }
    }
    Dispatchers.resetMain()
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
