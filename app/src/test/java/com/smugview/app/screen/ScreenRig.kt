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
import com.smugview.app.scenario.ScenarioRig
import com.smugview.app.ui.theme.SmugViewTheme
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 6 screen rig (design section 6): [ScenarioRig] (real repository, real in-memory Room, the fake
 * server, a real view model) plus a Compose rule and a Coil [ImageLoader] that answers every image with
 * a 1x1 bitmap and records the model of each request in [imageRequests] (what the avatar test reads).
 *
 * Every screen takes its `SmugViewModel` as a parameter, so `hiltViewModel()` is never called. The
 * test class owns the rule: `@get:Rule val compose = createComposeRule()`.
 * Call [close] from `@After`.
 */
class ScreenRig(val compose: ComposeContentTestRule, retrying: Boolean = false) {
    val rig = ScenarioRig(retrying)
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

    /** Renders [content] under the app theme. */
    fun setContent(content: @Composable () -> Unit) = compose.setContent { SmugViewTheme { content() } }

    /** Polls the Compose tree, advancing the clock, until [condition] holds (the rig's state arrives on other threads). */
    fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    fun close() {
        rig.close()
    }
}
