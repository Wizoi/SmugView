package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.AlbumImageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Everything the gallery screen shows about one album (design 3.4). One per album key; the loader
 * holds the current one and a few finished ones.
 */
data class AlbumState(
    val albumKey: String,
    val photos: List<AlbumImageData> = emptyList(),
    val tags: Set<String> = emptySet(),
    val title: String = "",
    val style: String = "Collage",
    val webUri: String = "",
    /** The album's own spinner: true from the moment it is selected until its stream ends or fails. */
    val loading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    /** Every page arrived (or the page cap was reached). Only complete states are kept for later. */
    val complete: Boolean = false
)

enum class AlbumSelection {
    /** Already the current album and still good: nothing was touched. */
    Same,
    /** A finished state of this album was in memory and is current again; no request was made. */
    Restored,
    /** A new run was started (the previous album's stream, if any, was cancelled). */
    Started
}

/** The handle a load function writes through. A write is dropped once this run is no longer the current one. */
class AlbumRun internal constructor(
    val albumKey: String,
    private val token: Long,
    private val loader: AlbumLoader
) {
    /** True while this run is the album on screen. */
    val isCurrent: Boolean get() = loader.isCurrentRun(albumKey, token)

    /** Applies [block] to this album's state. Returns false (and does nothing) for a stale run. */
    fun update(block: (AlbumState) -> AlbumState): Boolean = loader.write(albumKey, token, block)
}

/**
 * Step 3-5: the one owner of "what is in the gallery grid". Before this, one set of view-model
 * flows was written by every album's load, so album A's late pages landed in album B's grid.
 *
 *  - [select] makes the album current and cancels the previous album's stream.
 *  - Every write goes through [AlbumRun.update] / [update], which drop writes for an album that is not
 *    the current one (or for an older run of the same album).
 *  - The last [keepComplete] complete albums you left stay in memory; going back to one is instant.
 *    An album you leave before it finished is dropped and restarts if you return (Q8).
 *
 * The flows below are views of the current state and are updated together with it, so a reader on any
 * thread sees a consistent value right after a write.
 */
class AlbumLoader(
    private val defaultScope: () -> CoroutineScope,
    private val onChange: (AlbumState?) -> Unit = {},
    private val keepComplete: Int = 3
) {
    private val lock = Any()
    private var token = 0L
    private var job: Job? = null
    private val kept = LinkedHashMap<String, AlbumState>()

    private val _state = MutableStateFlow<AlbumState?>(null)
    val state: StateFlow<AlbumState?> = _state.asStateFlow()

    private val _photos = MutableStateFlow<List<AlbumImageData>>(emptyList())
    val photos: StateFlow<List<AlbumImageData>> = _photos.asStateFlow()

    private val _tags = MutableStateFlow<Set<String>>(emptySet())
    val tags: StateFlow<Set<String>> = _tags.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The error the grid shows instead of photos: only while there are no photos to show (R-46 keeps partial pages). */
    val blockingError: StateFlow<String?> = _error.asStateFlow()

    /** The album on screen, or null before the first [select] and after [reset]. */
    val currentKey: String? get() = _state.value?.albumKey

    /** Keys of the finished albums held for a quick return, oldest first. */
    val keptKeys: List<String> get() = synchronized(lock) { kept.keys.toList() }

    internal fun isCurrentRun(albumKey: String, run: Long): Boolean = synchronized(lock) {
        run == token && _state.value?.albumKey == albumKey
    }

    internal fun write(albumKey: String, run: Long, block: (AlbumState) -> AlbumState): Boolean = synchronized(lock) {
        if (run != token) return false
        applyLocked(albumKey, block)
    }

    /** Applies [block] to [albumKey]'s state if it is the current album; otherwise drops the write. */
    fun update(albumKey: String, block: (AlbumState) -> AlbumState): Boolean = synchronized(lock) {
        applyLocked(albumKey, block)
    }

    private fun applyLocked(albumKey: String, block: (AlbumState) -> AlbumState): Boolean {
        val cur = _state.value ?: return false
        if (cur.albumKey != albumKey) return false
        val next = block(cur)
        if (next !== cur) publishLocked(next)
        return true
    }

    private fun publishLocked(s: AlbumState?) {
        _state.value = s
        val p = s?.photos ?: emptyList()
        if (_photos.value !== p) _photos.value = p
        _tags.value = s?.tags ?: emptySet()
        _loading.value = s?.loading ?: false
        _status.value = s?.status
        _error.value = s?.error?.takeIf { s.photos.isEmpty() }
        onChange(s)
    }

    private fun reusable(s: AlbumState, target: String?): Boolean {
        if (s.error != null) return false
        if (!(s.complete || s.loading || s.photos.isNotEmpty())) return false
        return target == null || s.photos.any { it.imageKey == target }
    }

    /**
     * Makes [albumKey] the current album. [force] skips every reuse (a retry). [load] runs in [scope]
     * (the site's scope by default) and is cancelled when another album is selected; it writes only
     * through its [AlbumRun]. When [load] returns normally its spinner is cleared; it sets `complete`
     * itself, since only it knows whether every page arrived.
     */
    fun select(
        albumKey: String,
        target: String? = null,
        force: Boolean = false,
        scope: CoroutineScope = defaultScope(),
        context: CoroutineContext = EmptyCoroutineContext,
        initialStatus: String? = null,
        load: suspend (AlbumRun) -> Unit
    ): AlbumSelection = synchronized(lock) {
        val cur = _state.value
        if (!force && cur != null && cur.albumKey == albumKey && reusable(cur, target)) return AlbumSelection.Same

        // Leaving the current album: a stream still running is cancelled; only a complete one is kept.
        token++ // first: cancel() may run the stream's finally block right here on an immediate dispatcher
        job?.cancel()
        job = null
        if (cur != null && cur.albumKey != albumKey && cur.complete) {
            kept.remove(cur.albumKey)
            kept[cur.albumKey] = cur.copy(loading = false, status = null)
            while (kept.size > keepComplete) kept.remove(kept.keys.first())
        }

        val keptState = kept.remove(albumKey)
        if (!force && keptState != null && reusable(keptState, target)) {
            publishLocked(keptState)
            return AlbumSelection.Restored
        }

        publishLocked(AlbumState(albumKey = albumKey, loading = true, status = initialStatus))
        val run = AlbumRun(albumKey, token, this)
        job = scope.launch(context) {
            try {
                load(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                run.update { it.copy(error = e.message ?: "Failed to load album images") }
            } finally {
                run.update { if (it.loading || it.status != null) it.copy(loading = false, status = null) else it }
            }
        }
        AlbumSelection.Started
    }

    /** A site switch: cancel the stream and forget everything, including the kept albums. */
    fun reset() {
        synchronized(lock) {
            token++
            job?.cancel()
            job = null
            kept.clear()
            if (_state.value != null) publishLocked(null)
        }
    }
}
