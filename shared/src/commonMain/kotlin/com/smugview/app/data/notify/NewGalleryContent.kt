package com.smugview.app.data.notify

/** What one local notification carries. Pure, so the text and the tap payload are tested without a phone. */
data class NewGalleryContent(
    val identifier: String,
    val title: String,
    val body: String,
    /** nickname always; albumKey and albumTitle only when exactly one gallery (like the Android intent extras). */
    val userInfo: Map<String, String>
) {
    companion object {
        const val KEY_NICKNAME = "notify.nickname"
        const val KEY_ALBUM_KEY = "notify.albumKey"
        const val KEY_ALBUM_TITLE = "notify.albumTitle"

        /** One identifier per site, so a second run replaces the first instead of stacking. */
        fun identifierFor(nickname: String) = "new-galleries.$nickname"

        fun of(nickname: String, galleries: List<ListedGallery>): NewGalleryContent {
            val info = LinkedHashMap<String, String>()
            info[KEY_NICKNAME] = nickname
            val only = galleries.singleOrNull()
            val key = only?.albumKey
            if (only != null && key != null) {
                info[KEY_ALBUM_KEY] = key
                info[KEY_ALBUM_TITLE] = only.name
            }
            return NewGalleryContent(
                identifierFor(nickname),
                NewGalleryText.title(nickname, galleries.size),
                NewGalleryText.body(galleries.map { it.name }),
                info
            )
        }
    }
}

/** When the next background refresh may start. iOS decides the real time; this is the earliest we ask for. */
object NextRunPolicy {
    const val DEFAULT_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** iOS ignores anything shorter than about 15 minutes; never ask for less. */
    const val MIN_INTERVAL_MS = 15L * 60 * 1000

    fun earliestBeginMillis(nowMillis: Long, intervalMs: Long = DEFAULT_INTERVAL_MS): Long =
        nowMillis + intervalMs.coerceAtLeast(MIN_INTERVAL_MS)

    /** Whether the open-time check is due: never ran, or the last run is older than the interval. */
    fun dueOnOpen(nowMillis: Long, lastRunMillis: Long?, intervalMs: Long = DEFAULT_INTERVAL_MS): Boolean =
        lastRunMillis == null || nowMillis - lastRunMillis >= intervalMs
}

/** Lets the platform re-plan its background work after the followed set changed. */
fun interface FollowRescheduler {
    fun reschedule()
}
