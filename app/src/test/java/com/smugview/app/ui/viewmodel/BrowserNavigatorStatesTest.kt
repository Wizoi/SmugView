package com.smugview.app.ui.viewmodel

import com.smugview.app.data.db.CachedNode
import com.smugview.app.ui.text.Problem
import com.smugview.app.ui.text.Subject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Step 6-5 (design 3.3): what the Folders listing is allowed to be while the navigator works.
 *
 *  - a background relist (the site sync relisting the folder on screen) never puts `Loading` over the `Success` that
 *    is already showing, and never puts an `Error` over it either: the owner keeps the listing they were reading;
 *  - a site that could not be reached is published as `Error(problem)` through the navigator, the one writer.
 *
 * The host is a fake that records every [BrowserHost.render]: the listing as the UI saw it, in order.
 */
class BrowserNavigatorStatesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private fun node(id: String) = CachedNode(
        nodeId = id, parentNodeId = "root", type = "Folder", title = "Folder $id", description = null,
        access = "Public", passwordHint = null, uri = "/api/v2/node/$id", childNodesUri = null, albumUri = null
    )

    private class FakeHost(var listing: (nodeId: String, force: Boolean) -> Flow<Result<List<CachedNode>>>) : BrowserHost {
        val rendered = CopyOnWriteArrayList<BrowserUiState>()
        override fun rootId() = "root"
        override suspend fun savedPassword(nodeId: String): String? = null
        override suspend fun needsPassword(node: CachedNode) = false
        override fun requestPassword(node: CachedNode) {}
        override suspend fun cachedNode(nodeId: String): CachedNode? = null
        override fun children(nodeId: String, force: Boolean, password: String?) = listing(nodeId, force)
        override suspend fun albumLineage(albumKey: String): List<CachedNode>? = null
        override suspend fun nodeLineage(nodeId: String): List<CachedNode>? = null
        override suspend fun onLoadFailure(nodeId: String, password: String?, error: Throwable) =
            LoadFailure(BrowserUiState.Error(Problem.from(error, Subject.Folder)), popAfter = false)
        override fun showTab(tab: BrowserTab) {}
        override fun render(state: BrowserState) { rendered += state.listing }
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what; rendered=${rendered()}")
            Thread.sleep(10)
        }
    }

    private lateinit var host: FakeHost
    private fun rendered() = if (::host.isInitialized) host.rendered.toList() else emptyList()

    @Test fun `a background relist keeps the Success on screen and swaps in the new rows`() {
        host = FakeHost { _, force ->
            flow {
                if (force) delay(150) // the relist takes a moment: that moment used to show a spinner
                emit(Result.success(if (force) listOf(node("a"), node("b"), node("c")) else listOf(node("a"), node("b"))))
            }
        }
        val navigator = BrowserNavigator(scope, host)
        navigator.navigate(NavIntent.Root)
        awaitTrue("the first listing") { host.rendered.lastOrNull() is BrowserUiState.Success }

        navigator.navigate(NavIntent.Refresh(force = true, background = true))
        awaitTrue("the relisted rows") { (host.rendered.lastOrNull() as? BrowserUiState.Success)?.nodes?.size == 3 }

        val afterFirstSuccess = host.rendered.dropWhile { it !is BrowserUiState.Success }
        assertFalse("a spinner replaced the listing the owner was reading: $afterFirstSuccess", afterFirstSuccess.any { it is BrowserUiState.Loading })
    }

    @Test fun `a background relist that fails leaves the Success on screen`() {
        host = FakeHost { _, force ->
            flow { emit(if (force) Result.failure(IOException("offline")) else Result.success(listOf(node("a"), node("b")))) }
        }
        val navigator = BrowserNavigator(scope, host)
        navigator.navigate(NavIntent.Root)
        awaitTrue("the first listing") { host.rendered.lastOrNull() is BrowserUiState.Success }

        navigator.navigate(NavIntent.Refresh(force = true, background = true))
        Thread.sleep(300) // negative wait: nothing should arrive
        assertTrue("the failed sync replaced the listing: ${host.rendered.toList()}", host.rendered.last() is BrowserUiState.Success)
        assertEquals(2, (host.rendered.last() as BrowserUiState.Success).nodes.size)
    }

    @Test fun `a refresh the owner asked for still shows the spinner and then the failure`() {
        host = FakeHost { _, force ->
            flow { emit(if (force) Result.failure(IOException("offline")) else Result.success(listOf(node("a")))) }
        }
        val navigator = BrowserNavigator(scope, host)
        navigator.navigate(NavIntent.Root)
        awaitTrue("the first listing") { host.rendered.lastOrNull() is BrowserUiState.Success }

        navigator.navigate(NavIntent.Refresh(force = true))
        awaitTrue("the failure") { host.rendered.lastOrNull() is BrowserUiState.Error }
        assertEquals(Problem.OfflineNothingSaved(Subject.Folder), (host.rendered.last() as BrowserUiState.Error).problem)
    }

    @Test fun `a site that could not be reached is published as an Error with its problem`() {
        host = FakeHost { _, _ -> flow { } }
        val navigator = BrowserNavigator(scope, host)
        val problem = Problem.OfflineNothingSaved(Subject.Site)

        navigator.navigate(NavIntent.SiteFailed(problem))

        awaitTrue("the site failure") { host.rendered.lastOrNull() == BrowserUiState.Error(problem) }
        assertEquals(BrowserUiState.Error(problem), navigator.state.value.listing)
    }
}
