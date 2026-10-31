package com.smugview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.smugview.app.data.api.ApiHttp
import com.smugview.app.data.api.KtorSmugMugApi
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.Migrations
import com.smugview.app.data.offline.OfflineCollections
import com.smugview.app.data.offline.OfflineReader
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.diag.SyncReporter
import com.smugview.app.data.notify.IosPendingOpen
import com.smugview.app.data.offline.iosNetworkKindFlow
import com.smugview.app.data.offline.IosOfflineBackground
import com.smugview.app.ui.component.LocalCastExplainer
import com.smugview.app.data.cast.createCastManager
import com.smugview.app.data.cast.KeyValueCastExplainer
import com.smugview.app.data.cast.CastBridgeHost
import com.smugview.app.data.offline.OfflineSettings
import com.smugview.app.data.offline.OfflineDownloader
import com.smugview.app.data.offline.KtorOriginalFetcher
import com.smugview.app.data.offline.ForegroundOfflineScheduling
import com.smugview.app.data.security.PasswordStore
import com.smugview.app.data.notify.SiteFollow
import com.smugview.app.data.notify.SharedSiteFollow
import com.smugview.app.data.notify.NewestGalleries
import com.smugview.app.data.notify.NewGalleryRunner
import com.smugview.app.data.notify.NewGalleryCheck
import com.smugview.app.data.notify.IosGalleryAnnouncer
import com.smugview.app.data.notify.IosBackgroundTasks
import com.smugview.app.data.api.SmugMugApi
import com.smugview.app.util.UserDefaultsStore
import com.smugview.app.data.security.KeychainPasswordStore
import com.smugview.app.data.repository.KeyValueSyncStateStore
import com.smugview.app.data.offline.KeyValueOfflineSettings
import com.smugview.app.ui.IosPhotoFileOps
import com.smugview.app.ui.LocalPhotoFileOps
import com.smugview.app.ui.theme.SmugViewTheme
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.util.Toaster
import com.smugview.app.util.ioDispatcher
import com.smugview.shared.api.platformEngine
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * The iOS app: the same screens and navigation graph as Android, over a hand-written composition root (Android uses Hilt).
 * Written without a Mac to compile it: the macOS CI job is its first test.
 *
 * What is a stand-in until Stage 3: the password store (memory only, so "kept only this session" shows), the offline scheduler
 * (nothing runs in the background), casting (none found), sharing, the photo library and the video surface (see `PlatformActions.ios.kt`,
 * `VideoEngine.ios.kt`).
 */
fun IosAppViewController(): UIViewController = ComposeUIViewController { SmugViewIosApp() }

@Composable
fun SmugViewIosApp() {
    val viewModel = remember {
        IosRoot.viewModel().also { vm ->
            // CI test hook: `SIMCTL_CHILD_SMUGVIEW_DEMO_SITE=smugmugfilms xcrun simctl launch ...` opens that (public) site at start, as if
            // it had been tapped, so the screenshot shows real data. Unset in every normal launch.
            val demoSite = NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_SITE"] as? String
            if (!demoSite.isNullOrBlank()) vm.selectSite(demoSite)
            // CI: SIMCTL_CHILD_SMUGVIEW_DEMO_TAB=Hub|Folders|Search|TagSearch|Collections opens that tab (after the site has loaded).
            val demoTab = NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_TAB"] as? String
            if (!demoTab.isNullOrBlank()) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(2500)
                    runCatching { vm.setActiveTab(com.smugview.app.ui.viewmodel.BrowserTab.valueOf(demoTab)) }
                }
            }
            // CI trace: `SIMCTL_CHILD_SMUGVIEW_DEBUG=1` prints what the app thinks, to stdout, every 5 s (the key's length, never its value).
            if (NSProcessInfo.processInfo.environment["SMUGVIEW_DEBUG"] != null) {
                CoroutineScope(Dispatchers.Default).launch {
                    repeat(7) { n ->
                        println(
                            "smugview-debug t=${n * 5}s keyLength=${IosRoot.apiKeyLength} demoSite=$demoSite active=${vm.activeNickname.value} " +
                                "tab=${vm.activeTab.value} splash=${vm.splashState.value} profileLoaded=${vm.activeUserProfile.value != null}"
                        )
                        delay(5000)
                    }
                }
            }
        }
    }
    var banner by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        Toaster.sink = { message, _ -> banner = message }
        onDispose { Toaster.sink = { _, _ -> } }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3500)
            banner = null
        }
    }
    val photoFileOps = remember { IosPhotoFileOps() }
    // A tapped new-gallery notification (Swift hands it to IosPendingOpen): open that site, and the gallery when there was exactly one.
    val pendingOpen by IosPendingOpen.pending.collectAsState()
    LaunchedEffect(Unit) { IosBackgroundTasks.runOnOpen() }
    SmugViewTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalPhotoFileOps provides photoFileOps,
                    LocalCastExplainer provides KeyValueCastExplainer(UserDefaultsStore("smugview_prefs"))
                ) {
                    SmugViewNavigation(
                        viewModel = viewModel,
                        newGalleryFollow = IosRoot.siteFollow,
                        pendingOpen = pendingOpen,
                        onOpenHandled = { IosPendingOpen.clear() },
                        // CI: SIMCTL_CHILD_SMUGVIEW_DEMO_ROUTE=photo_detail/albumKey/imageKey (and the other routes in SmugViewNavigation).
                        startRoute = (NSProcessInfo.processInfo.environment["SMUGVIEW_DEMO_ROUTE"] as? String)?.takeIf { it.isNotBlank() }
                    )
                }
                banner?.let { message ->
                    Text(
                        text = message,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                            .background(Color(0xCC222222), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** The one set of long-lived objects behind the screens, built on first use. */
@OptIn(ExperimentalForeignApi::class)
private object IosRoot {
    private val documents: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: "."

    /** The SmugMug API key the build put in Info.plist (`SmugMugAPIKey`); empty when the build left it out. */
    private val apiKey: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("SmugMugAPIKey") as? String).orEmpty()

    /** For the CI trace only: how long the key is, never what it is. */
    val apiKeyLength: Int get() = apiKey.length

    private val database: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(name = "$documents/smugview.db")
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*Migrations.all)
            .setQueryCoroutineContext(ioDispatcher)
            .build()
    }

    private val api: SmugMugApi by lazy { KtorSmugMugApi(ApiHttp(HttpClient(platformEngine()))) }

    private val repository: SmugMugRepository by lazy {
        SmugMugRepository(
            api,
            database.collectionDao(),
            passwordStore,
            SyncReporter.NOOP,
            KeyValueSyncStateStore(UserDefaultsStore("smugview_sync_state"))
        )
    }

    private val passwordStore: PasswordStore by lazy { KeychainPasswordStore() }

    /** What the new-gallery check does before it looks: unlock the saved password folders, as Android does. */
    private val unlockSaved: suspend (String) -> Unit = { nickname -> repository.unlockSavedRoots(nickname, apiKey) }

    private val newGalleryCheck: NewGalleryCheck by lazy {
        val newest = NewestGalleries(api, apiKey)
        NewGalleryCheck(database.notifyDao()) { newest(it) }
    }

    /** The Hub's bell. Following keeps the background refresh request alive; unfollowing the last site cancels it. */
    val siteFollow: SiteFollow by lazy {
        val dao = database.notifyDao()
        SharedSiteFollow(dao, newGalleryCheck, unlockSaved) {
            CoroutineScope(Dispatchers.Default).launch {
                if (dao.enabledSites().isEmpty()) IosBackgroundTasks.cancel() else IosBackgroundTasks.scheduleNext()
            }
        }
    }

    private val offlineSettings: OfflineSettings by lazy { KeyValueOfflineSettings(UserDefaultsStore("smugview_offline_settings")) }

    private val offlineStore: OfflineStore by lazy { OfflineStore(database, documents) }

    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Casting runs one coroutine at a time on the UI thread, which the Google Cast SDK expects its calls on. */
    private val castScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Saved photos and kept galleries download while the app runs; a background task for the rest comes through `runBackgroundPass`. */
    private val offlineScheduling: ForegroundOfflineScheduling by lazy {
        // The image client has no cache and no content negotiation: originals are streamed to disk, not decoded.
        val downloader = OfflineDownloader(
            store = offlineStore,
            repo = repository,
            fetcher = KtorOriginalFetcher(HttpClient(platformEngine())),
            apiKey = { apiKey },
            settings = offlineSettings
        )
        ForegroundOfflineScheduling.create(schedulerScope, iosNetworkKindFlow(), offlineSettings, downloader, offlineStore).also { scheduling ->
            // A BGProcessingTask (registered at launch by Swift) runs a pass through this when iOS decides to wake the app.
            IosOfflineBackground.provider = { scheduling.runBackgroundPass() }
        }
    }

    private val viewModelInstance: SmugViewModel by lazy {
        val settings = offlineSettings
        val store = offlineStore
        IosBackgroundTasks.runner = NewGalleryRunner(database.notifyDao(), newGalleryCheck, unlockSaved, IosGalleryAnnouncer())
        SmugViewModel(
            apiKey = apiKey,
            sharedPrefs = UserDefaultsStore("smugview_prefs"),
            searchStatusPrefs = UserDefaultsStore("smugview_search_status"),
            showMessage = { message -> Toaster.show(message) },
            fileUri = { path -> "file://$path" },
            repository = repository,
            offline = OfflineCollections(database, store, offlineScheduling, settings),
            offlineReader = OfflineReader(database, store, settings, iosNetworkKindFlow()),
            castManager = createCastManager(CastBridgeHost.bridge, castScope),
            passwordStore = passwordStore,
            defaultDispatcher = Dispatchers.Default
        ).also {
            // CI end-to-end checks (`SMUGVIEW_DEMO_CHECK=video|offline`); does nothing when unset.
            DemoChecks.start(database, OfflineCollections(database, store, offlineScheduling, settings), documents)
            schedulerScope.launch {
                offlineScheduling.kickIfWanted()
                IosOfflineBackground.scheduleOfflineIfWanted()
            }
        }
    }

    /** One instance for the whole process: the phone UI and, later, the AirPlay external screen read the same state. */
    fun viewModel(): SmugViewModel = viewModelInstance
}
