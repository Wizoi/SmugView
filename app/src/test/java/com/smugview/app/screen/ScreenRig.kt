package com.smugview.app.screen

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.android.asCoroutineDispatcher
import com.smugview.app.scenario.ScenarioRig
import com.smugview.app.ui.theme.SmugViewTheme
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.smugview.app.ui.detail.PhotoDetailScreen
import com.smugview.app.ui.grid.PhotoGridScreen

/**
 * Phase 6 screen rig (design section 6): [ScenarioRig] (real repository, real in-memory Room, the fake
 * server, a real view model) plus a Compose rule and a Coil [ImageLoader] that answers every image with
 * a 1x1 bitmap and records the model of each request in [imageRequests] (what the avatar test reads).
 *
 * Every screen takes its `SmugViewModel` as a parameter, so `hiltViewModel()` is never called. The
 * test class owns the rule: `@get:Rule val compose = createComposeRule()`.
 * Call [close] from `@After`.
 */
class ScreenRig(
    val compose: ComposeContentTestRule,
    retrying: Boolean = false,
    repositoryDao: (com.smugview.app.data.db.CollectionDao) -> com.smugview.app.data.db.CollectionDao = { it }
) {
    // Dispatchers.Main is the Robolectric main looper, the real app's arrangement. Coil's AsyncImagePainter reads
    // Compose snapshot state from Dispatchers.Main and crashes on any other thread; the view model's work follows it.
    val rig = ScenarioRig(retrying, mainDispatcher = android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher(), repositoryDao = repositoryDao)
    val viewModel get() = rig.viewModel
    val server get() = rig.server

    /** The `data` of every Coil request, in order, as the string the app passed (a URL for the app's images). */
    val imageRequests = CopyOnWriteArrayList<String>()

    private val recorder = Interceptor { chain ->
        imageRequests += chain.request.data.toString()
        val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        SuccessResult(
            drawable = android.graphics.drawable.BitmapDrawable(rig.app.resources, bitmap),
            request = chain.request,
            dataSource = DataSource.MEMORY
        ) as ImageResult
    }

    init {
        // manifest = Config.NONE: register the activity the compose rule launches.
        shadowOf(rig.app.packageManager).addActivityIfNotPresent(
            ComponentName(rig.app.packageName, ComponentActivity::class.java.name)
        )
        Coil.setImageLoader(ImageLoader.Builder(rig.app).components { add(recorder) }.build())
    }

    /** The grid <-> viewer navigation of [setGalleryContent]; null until it is called. */
    var navController: NavHostController? = null
        private set

    /** How many times the grid or the viewer asked to go back (a Back or Go back tap). */
    val backClicks = AtomicInteger()

    val currentRoute: String? get() = navController?.currentBackStackEntry?.destination?.route

    /**
     * Design 6-7: the app's `photo_grid/{albumKey}` and `photo_detail/{albumKey}/{imageKey}` routes in a tiny `NavHost`, with the
     * app's own back handling (`navigateUp`), so a test can open a photo from the grid and come back, as `MainActivity` does.
     * With [startImageKey] the viewer is the first destination (its arguments are defaults), else the grid is. Going back from the first
     * destination is only counted.
     */
    fun setGalleryContent(albumKey: String, startImageKey: String? = null) {
        setContent {
            val nav = rememberNavController()
            navController = nav
            val back: () -> Unit = {
                backClicks.incrementAndGet()
                if (nav.previousBackStackEntry != null) nav.popBackStack()
            }
            NavHost(navController = nav, startDestination = if (startImageKey != null) VIEWER else GRID) {
                composable(GRID) {
                    PhotoGridScreen(
                        albumKey = albumKey,
                        albumTitle = "A gallery",
                        onNavigateToPhotoDetail = { imageKey -> nav.navigate(viewer(albumKey, imageKey)) },
                        onBackClick = back,
                        onNavigateToFolder = {},
                        onNavigateToCastController = {},
                        viewModel = viewModel
                    )
                }
                composable(
                    VIEWER,
                    arguments = listOf(
                        navArgument("albumKey") { type = NavType.StringType; defaultValue = albumKey },
                        navArgument("imageKey") { type = NavType.StringType; defaultValue = startImageKey ?: "" }
                    )
                ) { entry ->
                    PhotoDetailScreen(
                        albumKey = entry.arguments?.getString("albumKey") ?: albumKey,
                        targetImageKey = entry.arguments?.getString("imageKey") ?: "",
                        onBackClick = back,
                        onNavigateToFolder = {},
                        onNavigateToGallery = { _, _ -> },
                        onNavigateToKeywordImages = {},
                        onNavigateToCastController = {},
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    /** Opens the viewer on [imageKey] from the test thread, as a tap on a grid photo does. */
    fun openViewer(albumKey: String, imageKey: String) {
        compose.runOnUiThread { navController!!.navigate(viewer(albumKey, imageKey)) }
        settle()
    }

    /** Renders [content] under the app theme. */
    fun setContent(content: @Composable () -> Unit) = compose.setContent { SmugViewTheme { content() } }

    /**
     * Polls until [condition] holds, then settles. The rig's state arrives on OkHttp and IO threads and is handed
     * back to the Robolectric main looper (paused: the test thread is the main thread), so each turn idles that
     * looper and advances the Compose clock before looking. A state flip is not yet on screen: it can land on a
     * background thread after the last idle and before the check, so the condition is true while the tree still
     * shows the old state. On return the looper is idle and Compose has recomposed ([settle]), so an assertion
     * on the tree right after sees the state the condition saw. [message] names what was awaited on a timeout.
     */
    fun waitUntil(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            healUiDispatcher()
            compose.mainClock.advanceTimeByFrame()
            if (condition()) break
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out after ${timeoutMs}ms waiting for: $message; ${describeState()}")
            Thread.sleep(20)
        }
        settle()
    }

    /**
     * Robolectric's looper reset between tests can drop the message `AndroidUiDispatcher.Main` (a process-wide singleton that
     * Paging's `LazyPagingItems` presents on) had scheduled. Its two "already scheduled" flags then stay true, tasks queue
     * forever, and a grid reads 0 items while the view model is right. Re-posting its dispatch callback un-wedges it, and an
     * extra post is harmless because the dispatch tolerates an empty queue.
     */
    private fun healUiDispatcher() {
        val d = androidx.compose.ui.platform.AndroidUiDispatcher.Main[kotlin.coroutines.ContinuationInterceptor] ?: return
        fun field(name: String) = d.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(d)
        val queue = field("toRunTrampolined") as Collection<*>
        val handler = field("handler") as android.os.Handler
        val callback = field("dispatchCallback") as Runnable
        val stuck = synchronized(field("lock")) { queue.isNotEmpty() }
        if (stuck) handler.post(callback)
    }

    /** What the view model held when a wait timed out: the state is the diagnosis (never a title, password or key of the account). */
    private fun describeState(): String {
        val a = viewModel.albumState.value
        return "album=${a?.albumKey} photos=${a?.photos?.size} loading=${a?.loading} complete=${a?.complete} problem=${a?.problem} " +
            "prompt=${viewModel.passwordPromptNode?.nodeId} passwordError=${viewModel.passwordError} route=$currentRoute"
    }

    /**
     * Waits until the opened site's folder listing is on screen AND will not change under the test. Opening a
     * site starts a background site sync; when it finishes it relists the open folder in place (`NavIntent.Refresh`,
     * background), and that relist publishes `Loading` over the `Success` that was already showing. A test that
     * stops at the first `Success` then asserts against a spinner (the flake this fixes: `Success` and the Loading
     * flip are ~15ms apart, so it is the race that decides). So: the sync's runs have all finished, the listing
     * is `Success`, and the listing object has been unchanged for [stableMs] while the looper kept idling.
     */
    fun awaitSiteQuiet(timeoutMs: Long = 10_000, stableMs: Long = 150) {
        val deadline = System.currentTimeMillis() + timeoutMs
        waitUntil(timeoutMs, "the site sync finished and a Success listing") {
            rig.reporter.runs.isNotEmpty() && !rig.reporter.hasOpenRun() && viewModel.browserState.value is com.smugview.app.ui.viewmodel.BrowserUiState.Success
        }
        var seen: Any? = null
        var since = 0L
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            healUiDispatcher()
            compose.mainClock.advanceTimeByFrame()
            val now = System.currentTimeMillis()
            val key = Triple(viewModel.browserState.value, rig.reporter.runs.size, rig.reporter.hasOpenRun())
            if (key != seen) { seen = key; since = now }
            if (now - since >= stableMs && viewModel.browserState.value is com.smugview.app.ui.viewmodel.BrowserUiState.Success) break
            if (now > deadline) throw AssertionError("timed out after ${timeoutMs}ms waiting for: the listing to stop changing")
            Thread.sleep(10)
        }
        settle()
    }

    /** Idles the main looper and lets Compose recompose and lay out until nothing is pending. */
    fun settle() {
        repeat(3) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            healUiDispatcher()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    fun close() {
        rig.close()
    }

    companion object {
        const val GRID = "photo_grid"
        const val VIEWER = "photo_detail/{albumKey}/{imageKey}"
        fun viewer(albumKey: String, imageKey: String) = "photo_detail/$albumKey/$imageKey"
    }
}
