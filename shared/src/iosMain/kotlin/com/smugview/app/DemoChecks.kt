package com.smugview.app

import com.smugview.app.data.api.ApiException
import com.smugview.app.data.api.SmugMugApi
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
    fun start(db: AppDatabase, offline: OfflineCollections, documentsDir: String, api: SmugMugApi, apiKey: String) {
        val which = NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_CHECK"] as? String
        when (which) {
            "video" -> scope.launch { runVideo() }
            "offline" -> scope.launch { runOffline(db, offline, documentsDir) }
            "unlock" -> scope.launch { runUnlock(api, apiKey) }
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

    /**
     * Unlocks a password folder with the real iOS network stack, the way the password prompt does (the same `api.unlockNode` call), then lists
     * the folder's children. `SMUGVIEW_DEMO_UNLOCK_NODE` is the node (default the owner's Family folder) and `SMUGVIEW_DEMO_UNLOCK_PASSWORD` the
     * password, which comes from a GitHub secret. The password is NEVER printed: only its length, how many characters are not ASCII and
     * whether it starts or ends with a space, because those are what an iOS keyboard can change. Prints `smugview-check unlock ...`.
     */
    private suspend fun runUnlock(api: SmugMugApi, apiKey: String) {
        val env = NSProcessInfo.processInfo.environment
        val node = (env["SMUGVIEW_DEMO_UNLOCK_NODE"] as? String)?.takeIf { it.isNotBlank() } ?: "2sDN5x"
        val password = (env["SMUGVIEW_DEMO_UNLOCK_PASSWORD"] as? String).orEmpty()
        println(
            "smugview-check unlock start node=$node passwordLength=${password.length} nonAscii=${password.count { it.code > 127 }} " +
                "edgeSpace=${password.isNotEmpty() && (password.first() == ' ' || password.last() == ' ')} apiKeyLength=${apiKey.length}"
        )
        if (password.isEmpty()) {
            println("smugview-check unlock result=NO_PASSWORD")
            return
        }
        suspend fun children(label: String): Int {
            return try {
                val n = api.getNodeChildren(node, apiKey, count = 20).response.nodes.orEmpty().size
                println("smugview-check unlock $label children=$n")
                n
            } catch (e: ApiException) {
                println("smugview-check unlock $label children=error http=${e.code}")
                -1
            } catch (e: Throwable) {
                println("smugview-check unlock $label children=error ${e::class.simpleName}")
                -1
            }
        }
        val before = children("before")
        val code = try {
            api.unlockNode(node, apiKey, password, "true").code
        } catch (e: ApiException) {
            e.code
        } catch (e: Throwable) {
            println("smugview-check unlock post error ${e::class.simpleName}: ${e.message?.take(120)}")
            -1
        }
        println("smugview-check unlock post http=$code")
        val after = children("after")
        val verdict = when {
            code !in 200..299 -> "REJECTED_OR_FAILED http=$code"
            after > before -> "UNLOCKED children $before -> $after"
            after > 0 -> "UNLOCKED children=$after (already listed before)"
            else -> "POST_OK_BUT_STILL_EMPTY"
        }
        println("smugview-check unlock result=$verdict")
    }
}
