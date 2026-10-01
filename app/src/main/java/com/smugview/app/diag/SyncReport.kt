package com.smugview.app.diag

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class SyncKind { GallerySync, LaunchUnlock, SubtreeIndex, FolderTreeSync }

/** [Completed] is for runs that are not paginated (launch unlock, subtree walk). */
enum class StopReason { ReachedKnown, NoNextPage, Error, Cancelled, Interrupted, Completed }

/**
 * One unlock call. Deliberately has no password field: the type cannot carry one.
 * [result] is the repository's `UnlockResult` name (Success, Rejected, Transient).
 */
data class UnlockAttempt(
    val target: String,
    val via: String,
    val result: String,
    val httpCode: Int?,
    val exception: String?,
    val ms: Long,
    val actionId: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("target", target); put("via", via); put("result", result)
        httpCode?.let { put("httpCode", it) }
        exception?.let { put("exception", it) }
        put("ms", ms)
        actionId?.let { put("actionId", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = UnlockAttempt(
            target = o.optString("target"), via = o.optString("via"), result = o.optString("result"),
            httpCode = if (o.has("httpCode")) o.getInt("httpCode") else null,
            exception = if (o.has("exception")) o.getString("exception") else null,
            ms = o.optLong("ms"), actionId = if (o.has("actionId")) o.getString("actionId") else null
        )
    }
}

/**
 * A sync or unlock run (design §2.4). Hand-serialised with `org.json` (no Gson: the `diag` package is
 * not kept by R8). Fields are plain vars: a run is filled by the one coroutine that owns it, except
 * [unlocks], which is thread-safe because unlock attempts can arrive from other coroutines.
 *
 * `stopDetail` for [StopReason.ReachedKnown] is `page=<1-based> index=<0-based>`; for
 * [StopReason.Error] it is the exception class (plus HTTP code).
 */
class SyncRun(
    val runId: String,
    val kind: SyncKind,
    val nickname: String,
    val startedAt: Long
) {
    @Volatile var endedAt: Long? = null
    @Volatile var isFirstSync: Boolean? = null
    @Volatile var persistedCount: Int = 0
    @Volatile var stopMarker: String? = null
    @Volatile var pagesFetched: Int = 0
    @Volatile var albumsSeen: Int = 0
    @Volatile var albumsNullLastUpdated: Int = 0
    @Volatile var albumsWithParentNode: Int = 0
    @Volatile var albumsPasswordSecurity: Int = 0
    @Volatile var changedCount: Int = 0
    @Volatile var invalidatedParents: Int = 0
    @Volatile var stop: StopReason? = null
    @Volatile var stopDetail: String? = null
    @Volatile var savedKeys: Int = 0
    @Volatile var skippedAlreadyUnlocked: List<String> = emptyList()
    @Volatile var notes: String? = null
    @Volatile var newInIndex30d: Int? = null
    @Volatile var litDotNodes: Int? = null
    /** The site root's NodeID when the caller knew it; handed to the doctor, never serialized. */
    @Volatile var rootNodeId: String? = null
    val unlocks: MutableList<UnlockAttempt> = CopyOnWriteArrayList()

    fun toJson(): JSONObject = JSONObject().apply {
        put("runId", runId); put("kind", kind.name); put("nickname", nickname); put("startedAt", startedAt)
        endedAt?.let { put("endedAt", it) }
        isFirstSync?.let { put("isFirstSync", it) }
        put("persistedCount", persistedCount)
        stopMarker?.let { put("stopMarker", it) }
        put("pagesFetched", pagesFetched); put("albumsSeen", albumsSeen)
        put("albumsNullLastUpdated", albumsNullLastUpdated)
        put("albumsWithParentNode", albumsWithParentNode)
        put("albumsPasswordSecurity", albumsPasswordSecurity)
        put("changedCount", changedCount); put("invalidatedParents", invalidatedParents)
        stop?.let { put("stop", it.name) }
        stopDetail?.let { put("stopDetail", it) }
        put("savedKeys", savedKeys)
        put("skippedAlreadyUnlocked", JSONArray(skippedAlreadyUnlocked))
        notes?.let { put("notes", it) }
        newInIndex30d?.let { put("newInIndex30d", it) }
        litDotNodes?.let { put("litDotNodes", it) }
        put("unlocks", JSONArray(unlocks.map { it.toJson() }))
    }

    /** A compact, human-readable block for the report file. */
    fun summary(): String = buildString {
        append(runId).append(' ').append(kind.name)
        append(" stop=").append(stop?.name ?: "running")
        stopDetail?.let { append('(').append(it).append(')') }
        if (kind == SyncKind.GallerySync) {
            append(" first=").append(isFirstSync)
            append(" persisted=").append(persistedCount)
            append(" pages=").append(pagesFetched)
            append(" seen=").append(albumsSeen)
            append(" nullDate=").append(albumsNullLastUpdated)
            append(" withParentNode=").append(albumsWithParentNode)
            append(" pwSecurity=").append(albumsPasswordSecurity)
            append(" changed=").append(changedCount)
            append(" invalidatedParents=").append(invalidatedParents)
            newInIndex30d?.let { append(" newInIndex30d=").append(it) }
            litDotNodes?.let { append(" litDotNodes=").append(it) }
        }
        if (kind == SyncKind.LaunchUnlock) {
            append(" savedKeys=").append(savedKeys)
            append(" skippedAlreadyUnlocked=").append(skippedAlreadyUnlocked.size)
            append(" attempted=").append(unlocks.size)
        }
        endedAt?.let { append(" ms=").append(it - startedAt) }
        notes?.let { append(" notes=").append(it) }
        for (u in unlocks) {
            append("\n    unlock ").append(u.via).append(' ').append(u.target).append(" -> ").append(u.result)
            u.httpCode?.let { append(" http=").append(it) }
            u.exception?.let { append(" ex=").append(it) }
            append(' ').append(u.ms).append("ms")
        }
        if (kind == SyncKind.LaunchUnlock && skippedAlreadyUnlocked.isNotEmpty()) {
            append("\n    skipped: ").append(skippedAlreadyUnlocked.joinToString(","))
        }
    }

    internal fun applyJson(o: JSONObject) {
        if (o.has("endedAt")) endedAt = o.getLong("endedAt")
        if (o.has("isFirstSync")) isFirstSync = o.getBoolean("isFirstSync")
        persistedCount = o.optInt("persistedCount")
        stopMarker = if (o.has("stopMarker")) o.getString("stopMarker") else null
        pagesFetched = o.optInt("pagesFetched"); albumsSeen = o.optInt("albumsSeen")
        albumsNullLastUpdated = o.optInt("albumsNullLastUpdated")
        albumsWithParentNode = o.optInt("albumsWithParentNode")
        albumsPasswordSecurity = o.optInt("albumsPasswordSecurity")
        changedCount = o.optInt("changedCount"); invalidatedParents = o.optInt("invalidatedParents")
        stop = if (o.has("stop")) runCatching { StopReason.valueOf(o.getString("stop")) }.getOrNull() else null
        stopDetail = if (o.has("stopDetail")) o.getString("stopDetail") else null
        savedKeys = o.optInt("savedKeys")
        skippedAlreadyUnlocked = o.optJSONArray("skippedAlreadyUnlocked")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList()
        notes = if (o.has("notes")) o.getString("notes") else null
        newInIndex30d = if (o.has("newInIndex30d")) o.getInt("newInIndex30d") else null
        litDotNodes = if (o.has("litDotNodes")) o.getInt("litDotNodes") else null
        unlocks.clear()
        o.optJSONArray("unlocks")?.let { a ->
            for (i in 0 until a.length()) unlocks.add(UnlockAttempt.fromJson(a.getJSONObject(i)))
        }
    }

    companion object {
        fun fromJson(o: JSONObject): SyncRun {
            val r = SyncRun(
                o.getString("runId"), SyncKind.valueOf(o.getString("kind")),
                o.optString("nickname"), o.optLong("startedAt")
            )
            r.applyJson(o)
            return r
        }
    }
}
