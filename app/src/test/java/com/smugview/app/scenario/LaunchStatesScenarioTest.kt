package com.smugview.app.scenario

import androidx.lifecycle.SavedStateHandle
import com.smugview.app.data.repository.UnlockManager.Access
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import com.smugview.app.ui.text.UserMessages
import com.smugview.app.ui.viewmodel.BrowserUiState
import com.smugview.app.ui.viewmodel.SmugViewModel
import com.smugview.app.ui.viewmodel.SplashUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * Step 6-5 (design 3.3, N2): what the Folders tab and Home hold when the site's profile cannot be fetched.
 *
 * Fixture F: site idzifamily, root 4zqWw -> Family 2sDN5x (Password) -> School P4BKB -> gallery LCdk7F.
 * Before this step a failed profile left `browserState` at `Loading` for good (the failure went into a splash
 * state only the dead `SiteExplorerScreen` drew), `retryActiveSite` had no caller, and the Folders "Retry" did
 * nothing while no folder was open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LaunchStatesScenarioTest {
    private lateinit var rig: ScenarioRig

    @Before fun setUp() { rig = ScenarioRig() }
    @After fun tearDown() = rig.close()

    private val vm get() = rig.viewModel
    private fun offline() { rig.server.failWith = { UnknownHostException("offline") } }
    private fun online() { rig.server.failWith = null }
    private fun SmugViewModel.listed() = (browserState.value as? BrowserUiState.Success)?.nodes?.map { it.nodeId }

    @Test fun `offline with nothing cached the Folders tab says so within two seconds, and Try again reaches the root`() {
        offline()
        vm.selectSite("idzifamily")

        awaitUntil("browserState is an Error (it stays Loading)", 2_000) { vm.browserState.value is BrowserUiState.Error }
        val problem = Problem.OfflineNothingSaved(Subject.Site)
        assertEquals(BrowserUiState.Error(problem), vm.browserState.value)
        assertEquals(problem, vm.siteProblem.value)
        assertEquals(SplashUiState.Error(problem), vm.splashState.value)
        assertTrue(
            UserMessages.body(problem, "Idzifamily").startsWith("Idzifamily hasn't been opened on this phone recently, so there's nothing saved to show.")
        )

        online()
        vm.retryActiveSite()

        awaitUntil("the root listing after Try again", 10_000) { vm.listed()?.contains("2sDN5x") == true }
        assertEquals("4zqWw", vm.currentFolderId)
        assertEquals(null, vm.siteProblem.value)
        assertEquals("the retry opened the site", "idzifamily", vm.activeNickname.value)
    }

    @Test fun `a 503 on the profile is SmugMug having trouble, not a spinner`() {
        rig.server.respondWith("user/idzifamily", 503, times = 100)
        vm.selectSite("idzifamily")

        awaitUntil("browserState is an Error (it stays Loading)", 2_000) { vm.browserState.value is BrowserUiState.Error }
        val problem = Problem.SmugMugTrouble(Subject.Site, 503)
        assertEquals(BrowserUiState.Error(problem), vm.browserState.value)
        assertEquals("SmugMug is having trouble", UserMessages.heading(problem))
        assertTrue(UserMessages.body(problem, "Idzifamily").startsWith("SmugMug answered with error 503."))
    }

    @Test fun `a launch with a saved folder that is offline keeps the saved place for the retry (3-9 kept)`() {
        // Process one: Root -> Family -> School.
        rig.passwords.savePassword("2sDN5x", "family-pw")
        vm.selectSite("idzifamily")
        awaitUntil("A's root listing") { vm.listed()?.contains("2sDN5x") == true }
        awaitUntil("Family is unlocked at launch") { rig.repository.unlocks.access.value["2sDN5x"] == Access.Session }
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("2sDN5x")!! })
        awaitUntil("Family's listing") { vm.listed()?.contains("P4BKB") == true }
        vm.navigateToChildFolder(runBlocking { rig.repository.getNodeById("P4BKB")!! })
        awaitUntil("School's listing") { vm.listed()?.contains("LCdk7F") == true }
        val bundle = rig.savedState.savedStateProvider().saveState()

        // Process two starts with no network at all: the profile fails.
        offline()
        val restored = rig.restartProcess(SavedStateHandle.createHandle(bundle, null))
        awaitUntil("the launch failure is shown", 3_000) { restored.browserState.value is BrowserUiState.Error }
        assertEquals(Problem.OfflineNothingSaved(Subject.Site), (restored.browserState.value as BrowserUiState.Error).problem)

        // The network is back and the owner taps Try again: they are in School, not at the root.
        online()
        restored.retryActiveSite()
        awaitUntil("School's listing after Try again", 10_000) {
            restored.folderNavigationStack.map { it.nodeId } == listOf("2sDN5x", "P4BKB") &&
                (restored.browserState.value as? BrowserUiState.Success)?.nodes?.any { it.nodeId == "LCdk7F" } == true
        }
        assertEquals("P4BKB", restored.currentFolderId)
    }

    @Test fun `Home offline after the profile resolved says the galleries are not available, not that there are none`() {
        rig.server.failWith = { target -> if (target.contains("!albums")) UnknownHostException("offline") else null }
        vm.selectSite("idzifamily")

        awaitUntil("the Home albums request failed", 5_000) { vm.albumsProblem.value != null }
        assertEquals(Problem.OfflineNothingSaved(Subject.Site), vm.albumsProblem.value)
        assertEquals(UserMessages.HOME_OFFLINE, UserMessages.homeAlbums(vm.albumsProblem.value))
        awaitUntil("the details load finished") { !vm.isActiveSiteDetailsLoading.value }
        assertTrue(vm.activeSiteAlbums.value.isEmpty())

        // Back online and Try again: the problem goes, the galleries come.
        online()
        vm.retryActiveSite()
        awaitUntil("the galleries arrived", 10_000) { vm.activeSiteAlbums.value.isNotEmpty() }
        assertEquals(null, vm.albumsProblem.value)
    }
}
