package com.smugview.app

import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.OfflineCollection
import com.smugview.app.data.offline.OfflineCollections
import com.smugview.app.data.offline.OfflineNetworkRule
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.offline.createPlatformFileOps
import com.smugview.app.ui.detail.VideoDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * CI end-to-end checks, switched on by `SIMCTL_CHILD_SMUGVIEW_DEMO_CHECK=video|offline`. Each prints `smugview-check ...` lines to
 * stdout every 2 s; `.github/workflows/ios-checks.yml` reads them and decides PASS or FAIL. Unset in every normal launch: nothing runs.
 * Written without a Mac: the macOS CI job is the first compile.
 */
internal object DemoChecks {
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Called once from the app's composition root; [offline], [db] and [documentsDir] are only used by the `offline` check. */
    fun start(db: AppDatabase, offline: OfflineCollections, documentsDir: String) {
        val which = NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_CHECK"] as? String
        when (which) {
            "video" -> scope.launch { runVideo() }
            "offline" -> scope.launch { runOffline(db, offline, documentsDir) }
            else -> Unit
        }
    }

    /** Every 2 s for 30 s: is the engine there, is it playing, where is it. The workflow compares the positions. */
    private suspend fun runVideo() {
        repeat(16) { n ->
            val engine = VideoDebug.engine
            val line = if (engine == null) {
                "smugview-check video t=${n * 2} hasEngine=false isPlaying=false positionMs=0 durationMs=0"
            } else {
                // The engine's getters read AVPlayer properties; a thrown error is reported, not fatal.
                try {
                    "smugview-check video t=${n * 2} hasEngine=true isPlaying=${engine.isPlaying} " +
                        "positionMs=${engine.currentPosition} durationMs=${engine.duration}"
                } catch (e: Throwable) {
                    "smugview-check video t=${n * 2} hasEngine=true error=${e.message}"
                }
            }
            println(line)
            delay(2000)
        }
        println("smugview-check video done")
    }

    /**
     * Keeps one small gallery the way the "Keep offline" button does: a collection, then [OfflineCollections.keepGalleryOffline] (which
     * writes the `offline_galleries` row and kicks the scheduler). Then prints the counts of `offline_files` rows by state and the bytes
     * on disk under `Documents/offline` every 2 s for 90 s.
     *
     * The network rule: the default is Wi-Fi only. The scheduler's `iosNetworkKindFlow()` reports UNMETERED only for a non-expensive
     * Wi-Fi path; a simulator inherits the Mac's connection, which on a CI runner is usually wired (an "other" interface), so the pass
     * class would be ANY and the downloader would apply the Wi-Fi-only rule and hold the gallery back. The check therefore sets
     * WIFI_AND_MOBILE first (the same call as the settings dialog), so what it measures is the download, not the rule.
     */
    private suspend fun runOffline(db: AppDatabase, offline: OfflineCollections, documentsDir: String) {
        val nickname = (NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_SITE"] as? String).orEmpty()
        val albumKey = (NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_ALBUM"] as? String)?.takeIf { it.isNotBlank() } ?: "N44kNs"
        // Let the site load first (the real flow keeps a gallery the user has seen).
        delay(8000)
        try {
            offline.setGalleryNetwork(OfflineNetworkRule.WIFI_AND_MOBILE)
            val collectionId = db.collectionDao().createCollection(OfflineCollection(name = "CI check", siteNickname = nickname))
            offline.keepGalleryOffline(collectionId, albumKey, nickname, "CI check gallery")
            println("smugview-check offline kept album=$albumKey nickname=$nickname collection=$collectionId rule=WIFI_AND_MOBILE")
        } catch (e: Throwable) {
            println("smugview-check offline error keeping the gallery: ${e.message}")
        }
        val fs = createPlatformFileOps()
        val dir = "$documentsDir/${OfflineStore.DIR}"
        repeat(45) { n ->
            try {
                val files = db.offlineDao().allFiles()
                val done = files.count { it.state == OfflineStore.DONE }
                val failed = files.count { it.state == OfflineStore.FAILED }
                val galleries = db.offlineDao().allGalleries()
                    .joinToString(",") { g -> g.albumKey + ":" + g.state + (g.failure?.let { f -> "/$f" } ?: "") }
                val bytes = try {
                    fs.listFiles(dir).sumOf { name -> fs.length("$dir/$name") }
                } catch (e: Throwable) {
                    0L
                }
                println("smugview-check offline t=${n * 2} wanted=${files.size} done=$done failed=$failed bytes=$bytes galleries=$galleries")
            } catch (e: Throwable) {
                println("smugview-check offline t=${n * 2} error=${e.message}")
            }
            delay(2000)
        }
        println("smugview-check offline done")
    }
}
