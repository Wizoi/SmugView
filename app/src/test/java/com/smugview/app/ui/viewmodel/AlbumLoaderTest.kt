package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.AlbumImageData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of [AlbumLoader] itself (step 3-5), without a view model: who may write, what is kept. */
class AlbumLoaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val seen = mutableListOf<AlbumState?>()
    private val loader = AlbumLoader(defaultScope = { scope }, onChange = { seen += it })

    @After fun tearDown() { scope.coroutineContext[Job]?.cancel() }

    private fun img(key: String) = AlbumImageData(imageKey = key, title = key)

    /** A load that publishes [keys] at once and finishes complete. */
    private fun finished(key: String, keys: List<String>): AlbumSelection =
        loader.select(key) { run -> run.update { it.copy(photos = keys.map(::img), complete = true) } }

    @Test fun `a write for an album that is not current is dropped`() {
        finished("A", listOf("a1"))
        loader.select("B") { }

        assertFalse(loader.update("A") { it.copy(photos = listOf(img("late"))) })

        assertEquals(emptyList<String>(), loader.photos.value.map { it.imageKey })
        assertEquals("B", loader.currentKey)
    }

    @Test fun `a cancelled stream cannot write after another album was selected`() {
        val gate = CompletableDeferred<Unit>()
        var lateWrite: Boolean? = null
        loader.select("A") { run ->
            run.update { it.copy(photos = listOf(img("a1"))) }
            try { gate.await() } finally { lateWrite = run.update { it.copy(photos = it.photos + img("a2")) } }
        }
        loader.select("B") { run -> run.update { it.copy(photos = listOf(img("b1")), complete = true) } }

        assertEquals("A's job was cancelled, its finally block ran, and its write was dropped", false, lateWrite)
        assertEquals(listOf("b1"), loader.photos.value.map { it.imageKey })
    }

    @Test fun `title and webUri start empty for every album`() {
        loader.select("A") { run -> run.update { it.copy(title = "A", webUri = "https://a", complete = true) } }
        loader.select("B") { }

        assertEquals("", loader.state.value?.title)
        assertEquals("", loader.state.value?.webUri)
        assertEquals("", seen.last()?.webUri)
    }

    @Test fun `the last three complete albums are kept and the oldest is dropped`() {
        listOf("A", "B", "C", "D", "E").forEach { finished(it, listOf("${it.lowercase()}1")) }

        assertEquals("E is current", "E", loader.currentKey)
        assertEquals(listOf("B", "C", "D"), loader.keptKeys)
    }

    @Test fun `going back to a kept album restores it without running a load`() {
        finished("A", listOf("a1", "a2"))
        finished("B", listOf("b1"))
        var ran = false

        val outcome = loader.select("A") { ran = true }

        assertEquals(AlbumSelection.Restored, outcome)
        assertFalse(ran)
        assertEquals(listOf("a1", "a2"), loader.photos.value.map { it.imageKey })
        assertFalse(loader.loading.value)
        assertEquals(listOf("B"), loader.keptKeys)
    }

    @Test fun `an album left before it finished is not kept and restarts`() {
        val gate = CompletableDeferred<Unit>()
        loader.select("A") { run ->
            run.update { it.copy(photos = listOf(img("a1"))) }
            gate.await()
        }
        finished("B", listOf("b1"))

        assertEquals(listOf<String>(), loader.keptKeys)
        var ran = false
        assertEquals(AlbumSelection.Started, loader.select("A") { ran = true })
        assertTrue(ran)
    }

    @Test fun `selecting the current album again is a no-op unless it failed or is empty`() {
        finished("A", listOf("a1"))
        assertEquals(AlbumSelection.Same, loader.select("A") { error("must not run") })

        loader.update("A") { it.copy(complete = false, error = "boom") }
        assertEquals("a partial failure restarts", AlbumSelection.Started, loader.select("A") { })
    }

    @Test fun `a target image that is not in the kept album forces a fresh load`() {
        finished("A", listOf("a1"))

        assertEquals(AlbumSelection.Same, loader.select("A", target = "a1") { })
        assertEquals(AlbumSelection.Started, loader.select("A", target = "a9") { })
    }

    @Test fun `the grid error shows only while there are no photos`() {
        loader.select("A") { run -> run.update { it.copy(photos = listOf(img("a1")), error = "partial") } }
        assertNull(loader.blockingError.value)

        loader.update("A") { it.copy(photos = emptyList()) }
        assertEquals("partial", loader.blockingError.value)
    }

    @Test fun `reset forgets everything and cancels the stream`() {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        loader.select("A") { try { gate.await() } finally { cancelled = true } }
        finished("B", listOf("b1"))
        loader.reset()

        assertNull(loader.currentKey)
        assertEquals(emptyList<String>(), loader.keptKeys)
        assertEquals(emptyList<String>(), loader.photos.value.map { it.imageKey })
        assertTrue(cancelled)
    }
}
