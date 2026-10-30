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
import com.smugview.app.data.cast.CastDevice
import com.smugview.app.data.cast.CastManager
import com.smugview.app.data.cast.WebCompanionFailure
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.Migrations
import com.smugview.app.data.offline.NetworkClass
import com.smugview.app.data.offline.NetworkKind
import com.smugview.app.data.offline.OfflineCollections
import com.smugview.app.data.offline.OfflineReader
import com.smugview.app.data.offline.OfflineScheduling
import com.smugview.app.data.offline.OfflineStore
import com.smugview.app.data.offline.PassResult
import com.smugview.app.data.repository.SmugMugRepository
import com.smugview.app.diag.SyncReporter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    SmugViewTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalPhotoFileOps provides photoFileOps) {
                    SmugViewNavigation(viewModel = viewModel)
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

    fun viewModel(): SmugViewModel {
        val passwordStore = KeychainPasswordStore()
        val settings = KeyValueOfflineSettings(UserDefaultsStore("smugview_offline_settings"))
        val store = OfflineStore(database, documents)
        val repository = SmugMugRepository(
            KtorSmugMugApi(ApiHttp(HttpClient(platformEngine()))),
            database.collectionDao(),
            passwordStore,
            SyncReporter.NOOP,
            KeyValueSyncStateStore(UserDefaultsStore("smugview_sync_state"))
        )
        return SmugViewModel(
            apiKey = apiKey,
            sharedPrefs = UserDefaultsStore("smugview_prefs"),
            searchStatusPrefs = UserDefaultsStore("smugview_search_status"),
            showMessage = { message -> Toaster.show(message) },
            fileUri = { path -> "file://$path" },
            repository = repository,
            offline = OfflineCollections(database, store, NoOfflineScheduling, settings),
            offlineReader = OfflineReader(database, store, settings, flowOf(NetworkKind.UNMETERED)),
            castManager = NoCastManager,
            passwordStore = passwordStore,
            defaultDispatcher = Dispatchers.Default
        )
    }
}

/** Stage 3 registers a `BGAppRefreshTask`; until then saved photos are fetched only when the app asks directly. */
private object NoOfflineScheduling : OfflineScheduling {
    override suspend fun kick() {}
    override suspend fun kickIfWanted() {}
    override suspend fun after(result: PassResult, network: NetworkClass) {}
}

/** No casting on iOS in v1: nothing is discovered. */
private object NoCastManager : CastManager {
    override val discoveredDevices: StateFlow<List<CastDevice>> = MutableStateFlow(emptyList())
    override val activeDevice: StateFlow<CastDevice?> = MutableStateFlow(null)
    override val isCasting: StateFlow<Boolean> = MutableStateFlow(false)
    override val currentImageUri: StateFlow<String?> = MutableStateFlow(null)
    override val slideshowInterval: StateFlow<Int> = MutableStateFlow(5)
    override val isSlideshowPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override val volume: StateFlow<Float> = MutableStateFlow(0.5f)
    override val isMuted: StateFlow<Boolean> = MutableStateFlow(false)
    override val isWebCompanionActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val webCompanionUrl: StateFlow<String?> = MutableStateFlow(null)
    override val webCompanionFailure: StateFlow<WebCompanionFailure?> = MutableStateFlow(null)
    override fun startDiscovery() {}
    override fun stopDiscovery() {}
    override fun connectToDevice(device: CastDevice) {}
    override fun disconnect() {}
    override fun castImage(url: String, title: String) {}
    override fun castSlideshow(urls: List<String>, intervalSeconds: Int) {}
    override fun setSlideshowPlaying(playing: Boolean) {}
    override fun setSlideshowInterval(seconds: Int) {}
    override fun nextPhoto() {}
    override fun previousPhoto() {}
    override fun setVolume(volume: Float) {}
    override fun toggleMute() {}
    override fun getLocalIpAddress(): String? = null
}
