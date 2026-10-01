package com.smugview.app.data.repository

import com.smugview.app.data.db.CachedNode
import com.smugview.app.data.repository.SmugMugRepository.UnlockResult
import com.smugview.app.data.repository.SmugMugRepository.UnlockSummary
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.diag.DiagContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [UnlockManager] needs from the repository (the bodies already exist there). */
interface UnlockIo {
    /** A cached row by NodeID or AlbumKey, or null. */
    suspend fun getNodeByIdOrKey(idOrKey: String): CachedNode?

    /** A cached row by NodeID, or null. */
    suspend fun getNodeById(nodeId: String): CachedNode?

    /** The node as the API describes it, in memory only (never written); null when it can't be read. */
    suspend fun fetchNode(nodeId: String, apiKey: String): CachedNode?

    /** Which node on the lineage of [nodeId] holds the password (reads `!parents`). */
    suspend fun resolvePasswordRootNodeId(nodeId: String, apiKey: String): RootResolution

    suspend fun unlockNodeResult(nodeId: String, apiKey: String, password: String): UnlockResult

    suspend fun unlockAlbumResult(albumKey: String, apiKey: String, password: String): UnlockResult
}

/**
 * The one owner of "is there a session for this password root?" (design phase-3 3.4). Everything is
 * keyed by the NodeID of the password ROOT, the nearest node on the lineage whose own SecurityType is
 * Password (a password folder or a password gallery); a sub-folder or gallery under it shares its
 * root's session.
 *
 * - [Access.None]: nothing is known. [Access.Saved]: a saved password exists but no session is
 *   known (or the last attempt was inconclusive). [Access.Session]: an `!unlock` succeeded in this
 *   process, so the cookie jar holds the session. [Access.Invalid]: SmugMug rejected the saved
 *   password in this process.
 * - Nothing here ever writes or deletes a saved password. A rejection marks the root [Access.Invalid]
 *   and keeps the password: only the user, at a prompt for that root, may delete it (step 3-7).
 * - One `!unlock` request per root at a time: concurrent callers share it ([ensureSession]).
 */
class UnlockManager(
    private val io: UnlockIo,
    private val store: PasswordStore
) {
    enum class Access { None, Saved, Session, Invalid }

    private val _access = MutableStateFlow<Map<String, Access>>(emptyMap())

    /** The state of every root seen so far, by root NodeID. */
    val access: StateFlow<Map<String, Access>> = _access.asStateFlow()

    private val _sessionEpoch = MutableStateFlow(0)

    /** +1 every time a root becomes [Access.Session] (it was something else before). */
    val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

    // The flight outlives a cancelled caller: the callers that joined it still need its answer.
    private val flights = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = HashMap<String, Deferred<UnlockResult>>()

    fun accessOf(rootId: String): Access = _access.value[rootId] ?: Access.None

    private fun setAccess(rootId: String, to: Access) {
        var becameSession = false
        _access.update { map ->
            val before = map[rootId] ?: Access.None
            becameSession = to == Access.Session && before != Access.Session
            if (before == to) map else map + (rootId to to)
        }
        if (becameSession) _sessionEpoch.update { it + 1 }
    }

    /**
     * The password root that protects [idOrKey]: from the cached rows when one of them (self or an
     * ancestor) is a Password node, else from `!parents`. A node nothing above it protects answers
     * itself, as the old unlock did. Null when the lineage can't be read (offline, 429, 5xx).
     */
    suspend fun rootOf(idOrKey: String, apiKey: String): String? {
        val row = io.getNodeByIdOrKey(idOrKey)
        cachedRootOf(row)?.let { return it }
        return when (val r = io.resolvePasswordRootNodeId(row?.nodeId ?: idOrKey, apiKey)) {
            is RootResolution.Resolved -> r.nodeId
            RootResolution.NotProtected -> row?.nodeId ?: idOrKey
            RootResolution.Unknown -> null
        }
    }

    /** The nearest Password row on the cached chain (depth <= 32, cycle-safe), or null when the cache can't say. */
    private suspend fun cachedRootOf(start: CachedNode?): String? {
        val seen = mutableSetOf<String>()
        var current = start
        var depth = 0
        while (current != null && depth < 32 && seen.add(current.nodeId)) {
            if (current.access == "Password") return current.nodeId
            val parent = current.parentNodeId
            if (parent == null || parent == "root") return null
            current = io.getNodeById(parent)
            depth++
        }
        return null
    }

    /**
     * The saved password for [idOrKey]: under its password root's key first, then the copies older
     * versions wrote (under the key itself, its NodeID, its AlbumKey and its cached ancestors). Never
     * writes anything.
     */
    suspend fun passwordFor(idOrKey: String, apiKey: String): String? =
        savedFor(idOrKey, rootOf(idOrKey, apiKey))

    private suspend fun savedFor(idOrKey: String, rootId: String?): String? {
        rootId?.let { store.getPassword(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        val keys = LinkedHashSet<String>()
        keys += idOrKey
        val row = io.getNodeByIdOrKey(idOrKey)
        if (row != null) {
            keys += row.nodeId
            keys += row.getAlbumKey()
            val seen = mutableSetOf(row.nodeId)
            var parentId = row.parentNodeId
            var depth = 0
            while (parentId != null && parentId != "root" && depth < 32 && seen.add(parentId)) {
                keys += parentId
                val parent = io.getNodeById(parentId) ?: break
                keys += parent.getAlbumKey()
                parentId = parent.parentNodeId
                depth++
            }
        }
        for (key in keys) store.getPassword(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    /**
     * Makes sure a session exists for the root that protects [idOrKey]. [password] is the one the
     * caller holds (a typed one that is not saved yet, say); without it the saved password is used.
     *
     * - [Access.Session]: [UnlockResult.Success] at once, no request.
     * - [Access.Invalid] and the password in hand is the saved, rejected one: [UnlockResult.Rejected]
     *   at once, no request. A different password is tried.
     * - Otherwise the root is unlocked (`node!unlock` for a folder, `album!unlock` for a gallery),
     *   one request at a time per root: concurrent callers share it. Success becomes Session,
     *   Rejected becomes Invalid (password kept), Transient (offline, 429, 5xx) stays Saved.
     * - No password at all, or a lineage that can't be read: Rejected or Transient, no state change.
     */
    suspend fun ensureSession(idOrKey: String, apiKey: String, password: String? = null): UnlockResult =
        ensure(idOrKey, apiKey, password, readGot401 = false)

    /**
     * A read with this root's credentials just failed with 401 (or an unlocked-looking empty answer).
     * A root believed to be in Session has expired (lifetime unverified): it goes back to Saved and is
     * unlocked again, once; callers already unlocking it share that request.
     */
    suspend fun reauthorize(idOrKey: String, apiKey: String, password: String? = null): UnlockResult =
        ensure(idOrKey, apiKey, password, readGot401 = true)

    private suspend fun ensure(idOrKey: String, apiKey: String, password: String?, readGot401: Boolean): UnlockResult {
        val rootId = rootOf(idOrKey, apiKey) ?: return UnlockResult.Transient
        val saved = savedFor(idOrKey, rootId)
        val pw = password?.takeIf { it.isNotEmpty() } ?: saved
        val actionId = DiagContext.currentActionId() ?: DiagContext.newActionId("unlock")

        val flight: Deferred<UnlockResult> = lock.withLock {
            inFlight[rootId]?.let { return@withLock it }
            val state = accessOf(rootId)
            if (state == Access.Session && !readGot401) return UnlockResult.Success
            if (state == Access.Invalid && pw != null && pw == saved) return UnlockResult.Rejected
            if (pw == null) return UnlockResult.Rejected
            if (state == Access.Session) setAccess(rootId, Access.Saved)
            else if (state == Access.None && saved != null) setAccess(rootId, Access.Saved)
            flights.async(DiagContext.element(actionId)) {
                // This scope is never cancelled, so a flight always reaches the bookkeeping below.
                val result = try {
                    unlockRoot(rootId, idOrKey, apiKey, pw)
                } catch (e: Exception) {
                    UnlockResult.Transient
                }
                lock.withLock {
                    when (result) {
                        UnlockResult.Success -> setAccess(rootId, Access.Session)
                        UnlockResult.Rejected -> setAccess(rootId, Access.Invalid)
                        UnlockResult.Transient -> if (saved != null && accessOf(rootId) != Access.Session) setAccess(rootId, Access.Saved)
                    }
                    inFlight.remove(rootId)
                }
                result
            }.also { inFlight[rootId] = it }
        }
        return flight.await()
    }

    private suspend fun unlockRoot(rootId: String, idOrKey: String, apiKey: String, password: String): UnlockResult {
        val root = io.getNodeById(rootId) ?: io.fetchNode(rootId, apiKey)
        if (root != null) {
            if (root.type == "Folder") return io.unlockNodeResult(root.nodeId, apiKey, password)
            val albumKey = root.getAlbumKey()
            return if (albumKey.isNotEmpty()) io.unlockAlbumResult(albumKey, apiKey, password)
            else io.unlockNodeResult(root.nodeId, apiKey, password)
        }
        // The root row is unknown and unreadable: the key may be a NodeID or an AlbumKey.
        val asNode = io.unlockNodeResult(idOrKey, apiKey, password)
        if (asNode == UnlockResult.Success) return asNode
        val asAlbum = io.unlockAlbumResult(idOrKey, apiKey, password)
        return when {
            asAlbum == UnlockResult.Success -> asAlbum
            asNode == UnlockResult.Transient || asAlbum == UnlockResult.Transient -> UnlockResult.Transient
            else -> UnlockResult.Rejected
        }
    }

    /**
     * The launch unlock (Phase 2) opened or failed to open these roots: Success is a Session,
     * Rejected is Invalid (the password stays saved), Transient is Saved unless a session already
     * exists. A summary with no per-root detail changes nothing.
     */
    fun recordLaunch(summary: UnlockSummary) {
        for ((rootId, result) in summary.results) {
            when (result) {
                UnlockResult.Success -> setAccess(rootId, Access.Session)
                UnlockResult.Rejected -> setAccess(rootId, Access.Invalid)
                UnlockResult.Transient -> if (accessOf(rootId) != Access.Session) setAccess(rootId, Access.Saved)
            }
        }
    }
}
