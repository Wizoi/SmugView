package com.smugview.app.data.notify

import com.smugview.app.data.db.NotifyDao
import kotlin.coroutines.cancellation.CancellationException

/** Tells the owner about [galleries] of [nickname]. Returns true only when the notification was really posted. */
fun interface GalleryAnnouncer {
    suspend fun announce(nickname: String, galleries: List<ListedGallery>): Boolean
}

/**
 * One background run: every followed site, one after the other. A site that fails (an unlock that throws, a listing that
 * cannot be had, a notification that cannot be posted) never stops the next one, and nothing is marked as told unless the
 * announcement was posted.
 */
class NewGalleryRunner(
    private val dao: NotifyDao,
    private val check: NewGalleryCheck,
    private val unlock: suspend (nickname: String) -> Unit,
    private val announcer: GalleryAnnouncer
) {
    suspend fun runAll(): Map<String, CheckResult> {
        val results = LinkedHashMap<String, CheckResult>()
        for (site in dao.enabledSites()) {
            val nickname = site.nickname
            // Galleries under a password folder are only listed with the session cookie (finding #16). A failed unlock
            // still leaves the public galleries to check.
            try { unlock(nickname) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            val result = check.run(nickname)
            results[nickname] = result
            if (result is CheckResult.Found) {
                val posted = try {
                    announcer.announce(nickname, result.galleries)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (posted) check.markNotified(nickname, result.galleries)
            }
        }
        return results
    }
}
