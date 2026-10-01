package com.smugview.app.ui.viewmodel

import com.smugview.app.data.api.SmugMugErrorMapper
import com.smugview.app.data.db.CachedNode
import com.smugview.app.diag.DiagContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the Folders tab is: the breadcrumb [stack], the [listing] shown for it, and where Back goes
 * after a jump from Search ([returnToSearch], the stack before the jump). The only owner of that data
 * (design 3.2, R-13): the ViewModel's `folderNavigationStack`, `currentFolderId`, `browserState` and
 * `savedFolderStateBeforeSearch` are read-only mirrors written by [BrowserHost.render].
 *
 * [rootId] is the site root's NodeID (it is the current folder while [stack] is empty).
 */
data class BrowserState(
    val stack: List<CachedNode> = emptyList(),
    val listing: BrowserUiState = BrowserUiState.Loading,
    val returnToSearch: List<CachedNode>? = null,
    val rootId: String? = null
) {
    val currentId: String? get() = stack.lastOrNull()?.nodeId ?: rootId
}

/** Every way the Folders tab can move. [BrowserNavigator.navigate] is the only entry point. */
sealed interface NavIntent {
    /** The site root, empty breadcrumb. */
    object Root : NavIntent
    /** Tap a folder in the listing: push it (after the access check) and list it. */
    data class Child(val node: CachedNode) : NavIntent
    /** Back. [viaSearch] false is a plain pop (no return to the Search tab). */
    data class Back(val viaSearch: Boolean = true) : NavIntent
    /** Breadcrumb tap: keep the stack up to [index] and list that folder. */
    data class ToIndex(val index: Int) : NavIntent
    /** List the current folder again. [background] ones (a sync relisted it) never cancel a user's move. */
    data class Refresh(val force: Boolean = false, val background: Boolean = false) : NavIntent
    /** A folder opened from Search: remember where Back returns to, then push it. */
    data class FromSearch(val node: CachedNode) : NavIntent
    /** A gallery was opened elsewhere (Search, Home, Collections): the breadcrumb and listing move to its folder. */
    data class Reveal(val galleryKey: String) : NavIntent
    /** A folder opened by id (a Collections bookmark): the breadcrumb is built from its lineage. */
    data class Shortcut(val nodeId: String) : NavIntent
}

/** What the host wants done when a folder's listing failed (the 401 handling stays in the host). */
data class LoadFailure(val listing: BrowserUiState?, val popAfter: Boolean)

/** The ViewModel-side services the navigator needs. */
interface BrowserHost {
    /** The site root's NodeID, once the profile resolved. */
    fun rootId(): String?
    /** A saved or inherited password for [nodeId], or null. May read the network (`!parents`). */
    suspend fun savedPassword(nodeId: String): String?
    /** Ask the owner for [node]'s password. */
    fun requestPassword(node: CachedNode)
    /** Cache-first listing of a folder. */
    fun children(nodeId: String, force: Boolean, password: String?): Flow<Result<List<CachedNode>>>
    /** Ancestors of a gallery, root-most first, without the gallery and without the site root. */
    suspend fun albumLineage(albumKey: String): List<CachedNode>?
    /** Lineage of a node, self first, ending at the site root. Read-only. */
    suspend fun nodeLineage(nodeId: String): List<CachedNode>?
    /** A listing failed with [error] while [nodeId] was current. */
    suspend fun onLoadFailure(nodeId: String, password: String?, error: Throwable): LoadFailure
    fun showTab(tab: BrowserTab)
    /** Write the mirrors. Called only from the navigation job, on Main. */
    fun render(state: BrowserState)
}

/**
 * One per [SiteSession]. One state, one navigation job, one entry point:
 *  - [navigate] cancels the previous navigation (and its load): the latest intent wins.
 *  - The access check runs inside the job, so a cancelled tap can't push.
 *  - A listing publishes only while `state.currentId` is still the folder it was requested for.
 */
class BrowserNavigator(
    private val scope: CoroutineScope,
    private val host: BrowserHost
) {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private var job: Job? = null
    private var pending: NavIntent? = null

    val currentId: String? get() = _state.value.currentId

    /** Whether [NavIntent.Back] would do anything right now (a snapshot; the move itself is asynchronous). */
    fun canGoBack(viaSearch: Boolean = true): Boolean {
        val s = _state.value
        if (viaSearch && s.returnToSearch != null) return true
        if (s.stack.isEmpty()) return false
        return (s.stack.dropLast(1).lastOrNull()?.nodeId ?: host.rootId()) != null
    }

    fun navigate(intent: NavIntent) {
        synchronized(this) {
            if (intent is NavIntent.Refresh && intent.background && job?.isActive == true && pending !is NavIntent.Refresh) {
                return // a sync relisted the folder while the owner is moving: the move will list fresh rows anyway
            }
            if (intent is NavIntent.Child) {
                // A double tap is one push: the node is already on top, or this very tap is in flight.
                if (_state.value.stack.lastOrNull()?.nodeId == intent.node.nodeId) return
                if (job?.isActive == true && pending == intent) return
            }
            job?.cancel()
            pending = intent
            val mine = scope.launch(DiagContext.element(DiagContext.newActionId("folder")), start = CoroutineStart.LAZY) {
                try {
                    handle(intent)
                } finally {
                    val me = kotlinx.coroutines.currentCoroutineContext()[Job]
                    synchronized(this@BrowserNavigator) { if (job === me) pending = null }
                }
            }
            job = mine
            mine.start()
        }
    }

    private fun set(new: BrowserState) {
        _state.value = new
        host.render(new)
    }

    /** The listing publish, checked by token: only if [requestedId] is still the current folder. */
    private fun publish(requestedId: String, listing: BrowserUiState) {
        val s = _state.value
        if (s.currentId != requestedId) return
        set(s.copy(listing = listing))
    }

    private suspend fun handle(intent: NavIntent) {
        host.rootId()?.let { if (it != _state.value.rootId) set(_state.value.copy(rootId = it)) }
        when (intent) {
            NavIntent.Root -> {
                val root = host.rootId() ?: return
                set(_state.value.copy(stack = emptyList(), rootId = root))
                load(root, force = false)
            }
            is NavIntent.Child -> child(intent.node)
            is NavIntent.Back -> back(intent.viaSearch)
            is NavIntent.ToIndex -> {
                val s = _state.value
                if (intent.index < 0) {
                    handle(NavIntent.Root)
                } else if (intent.index < s.stack.size) {
                    set(s.copy(stack = s.stack.take(intent.index + 1)))
                    load(s.stack[intent.index].nodeId, force = false)
                }
            }
            is NavIntent.Refresh -> {
                val id = _state.value.currentId ?: return
                load(id, intent.force)
            }
            is NavIntent.FromSearch -> {
                val s = _state.value
                if (s.returnToSearch == null) set(s.copy(returnToSearch = s.stack))
                host.showTab(BrowserTab.Folders)
                child(intent.node)
            }
            is NavIntent.Reveal -> reveal(intent.galleryKey)
            is NavIntent.Shortcut -> shortcut(intent.nodeId)
        }
    }

    private suspend fun child(node: CachedNode) {
        val savedPassword = host.savedPassword(node.nodeId)
        if ((node.access == "Password" || node.access == "Inherited") && savedPassword == null) {
            host.requestPassword(node)
            return
        }
        set(_state.value.copy(stack = _state.value.stack + node))
        // Deliberately NOT markNodeAsViewed here: that marks every gallery beneath the folder
        // as viewed, clearing dots the user never looked at. See
        // SmugViewModelTest.navigatingIntoFolder_doesNotMarkItsGalleriesViewed.
        load(node.nodeId, force = false)
    }

    private suspend fun back(viaSearch: Boolean) {
        val s = _state.value
        val saved = s.returnToSearch
        if (viaSearch && saved != null && s.stack.size <= saved.size + 1) {
            val restored = s.copy(stack = saved, returnToSearch = null)
            set(restored)
            host.showTab(BrowserTab.Search)
            restored.currentId?.let { load(it, force = false) }
            return
        }
        if (s.stack.isEmpty()) return
        val popped = s.copy(stack = s.stack.dropLast(1))
        set(popped)
        popped.currentId?.let { load(it, force = false) }
    }

    /** Q3: opening a gallery elsewhere moves the Folders tab, breadcrumb and listing together. */
    private suspend fun reveal(galleryKey: String) {
        val lineage = try {
            host.albumLineage(galleryKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val s = _state.value
        val sameFolder = s.stack.map { it.nodeId } == lineage.map { it.nodeId }
        val moved = s.copy(stack = lineage)
        set(moved)
        val id = moved.currentId ?: return
        if (sameFolder && s.listing is BrowserUiState.Success) return
        // Quiet: the owner is looking at a gallery, so a locked or failing folder must not prompt over it.
        load(id, force = false, quiet = true)
    }

    private suspend fun shortcut(nodeId: String) {
        val chain = try {
            host.nodeLineage(nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (chain.isNullOrEmpty()) {
            set(_state.value.copy(listing = BrowserUiState.Error("Couldn't open that folder. Check your connection and try again.")))
            return
        }
        val root = host.rootId()
        val stack = chain.filter { it.nodeId != root }.reversed()
        set(_state.value.copy(stack = stack))
        (stack.lastOrNull()?.nodeId ?: root)?.let { load(it, force = false) }
    }

    private suspend fun load(id: String, force: Boolean, quiet: Boolean = false) {
        publish(id, BrowserUiState.Loading)
        val password = host.savedPassword(id)
        host.children(id, force, password).collect { result ->
            if (_state.value.currentId != id) return@collect
            result.fold(
                onSuccess = { nodes -> publish(id, BrowserUiState.Success(nodes)) },
                onFailure = { error ->
                    if (quiet) {
                        publish(id, BrowserUiState.Error(SmugMugErrorMapper.userMessage(error, "Failed to load hierarchy")))
                    } else {
                        val failure = host.onLoadFailure(id, password, error)
                        failure.listing?.let { publish(id, it) }
                        if (failure.popAfter && _state.value.currentId == id) navigate(NavIntent.Back(viaSearch = false))
                    }
                }
            )
        }
    }
}
