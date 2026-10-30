package com.smugview.app.diag

import com.smugview.app.util.CopyOnWriteList
import kotlin.concurrent.Volatile

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
)

/**
 * A sync or unlock run (design §2.4). Serialised by hand in the app (`SyncReportJson.kt`, `org.json`). Fields are plain vars: a run is filled by the one coroutine that owns it, except
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
    val unlocks: MutableList<UnlockAttempt> = CopyOnWriteList()

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
}
