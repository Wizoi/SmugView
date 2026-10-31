package com.smugview.app.data.offline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundOfflineSchedulingTest {
    private class Rig(
        scope: TestScope,
        kind: NetworkKind = NetworkKind.UNMETERED,
        val rule: OfflineNetworkRule = OfflineNetworkRule.WIFI_ONLY
    ) {
        val network = MutableStateFlow(kind)
        val passes = mutableListOf<NetworkClass>()
        val delays = mutableListOf<Long>()
        var wanted = 1
        var retryAt: Long? = null
        var results = ArrayDeque<PassResult>()
        var gate: CompletableDeferred<Unit>? = null
        var galleryOnlyAsked = mutableListOf<Boolean>()

        val scheduling = ForegroundOfflineScheduling(
            scope = scope.backgroundScope,
            network = network,
            settings = InMemoryOfflineSettings(rule),
            runPass = { c ->
                passes += c
                gate?.await()
                results.removeFirstOrNull() ?: PassResult(passes.size, 1, 0, 10, more = false)
            },
            countWanted = { wanted },
            earliestRetryAt = { galleryOnlyAsked += it; retryAt },
            delayFn = { delays += it; kotlinx.coroutines.delay(it) },
            clock = { scope.currentTime }
        )
    }

    @Test
    fun a_kick_runs_one_pass_on_the_network_class() = runTest {
        val rig = Rig(this)
        rig.scheduling.kick()
        runCurrent()
        assertEquals(listOf(NetworkClass.UNMETERED), rig.passes)
    }

    @Test
    fun metered_wifi_and_mobile_run_the_any_class() = runTest {
        val rig = Rig(this, NetworkKind.METERED_WIFI)
        rig.scheduling.kick()
        runCurrent()
        rig.network.value = NetworkKind.METERED_OTHER
        rig.scheduling.kick()
        runCurrent()
        assertEquals(listOf(NetworkClass.ANY, NetworkClass.ANY), rig.passes)
    }

    @Test
    fun no_network_runs_nothing_until_it_comes_back() = runTest {
        val rig = Rig(this, NetworkKind.NONE)
        rig.scheduling.kick()
        runCurrent()
        assertEquals(emptyList(), rig.passes)
        rig.network.value = NetworkKind.UNMETERED
        runCurrent()
        assertEquals(listOf(NetworkClass.UNMETERED), rig.passes)
    }

    @Test
    fun a_network_that_comes_back_with_nothing_wanted_runs_nothing() = runTest {
        val rig = Rig(this, NetworkKind.NONE)
        rig.wanted = 0
        runCurrent()
        rig.network.value = NetworkKind.UNMETERED
        runCurrent()
        assertEquals(emptyList(), rig.passes)
    }

    @Test
    fun kicks_while_a_pass_runs_coalesce_into_one_more_pass() = runTest {
        val rig = Rig(this)
        rig.gate = CompletableDeferred()
        rig.scheduling.kick()
        runCurrent()
        repeat(5) { rig.scheduling.kick() }
        runCurrent()
        assertEquals(1, rig.passes.size) // still the first one, never two at once
        rig.gate!!.complete(Unit)
        runCurrent()
        assertEquals(2, rig.passes.size)
    }

    @Test
    fun kick_if_wanted_does_nothing_when_nothing_is_wanted() = runTest {
        val rig = Rig(this)
        rig.wanted = 0
        rig.scheduling.kickIfWanted()
        runCurrent()
        assertEquals(0, rig.passes.size)
        rig.wanted = 2
        rig.scheduling.kickIfWanted()
        runCurrent()
        assertEquals(1, rig.passes.size)
    }

    @Test
    fun a_pass_with_more_runs_again_at_once() = runTest {
        val rig = Rig(this)
        rig.results += PassResult(1, 5, 0, 50, more = true)
        rig.results += PassResult(2, 5, 0, 50, more = false)
        rig.scheduling.kick()
        runCurrent()
        assertEquals(2, rig.passes.size)
        assertEquals(0L, currentTime)
        assertEquals(emptyList(), rig.delays)
    }

    @Test
    fun a_failed_file_is_retried_after_the_delay_not_before() = runTest {
        val rig = Rig(this)
        rig.retryAt = 100_000L // 100 s from the start
        rig.results += PassResult(1, 0, 1, 0, more = false)
        rig.scheduling.kick()
        runCurrent()
        assertEquals(1, rig.passes.size)
        assertEquals(listOf(100_000L), rig.delays)
        advanceTimeBy(99_000)
        assertEquals(1, rig.passes.size)
        rig.retryAt = null
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, rig.passes.size)
        advanceTimeBy(1_000_000)
        assertEquals(2, rig.passes.size)
        assertEquals(1, rig.delays.size)
    }

    @Test
    fun a_retry_due_now_waits_at_least_the_minimum() = runTest {
        val rig = Rig(this)
        rig.retryAt = 0L
        rig.results += PassResult(1, 0, 1, 0, more = false)
        rig.scheduling.kick()
        runCurrent()
        assertEquals(listOf(ForegroundOfflineScheduling.MIN_RETRY_DELAY_MS), rig.delays)
    }

    @Test
    fun the_retry_asks_for_gallery_files_only_when_the_rule_allows_it() = runTest {
        val wifiOnlyMobile = Rig(this, NetworkKind.METERED_OTHER, OfflineNetworkRule.WIFI_ONLY)
        wifiOnlyMobile.scheduling.kick()
        runCurrent()
        val both = Rig(this, NetworkKind.METERED_OTHER, OfflineNetworkRule.WIFI_AND_MOBILE)
        both.scheduling.kick()
        runCurrent()
        val wifi = Rig(this, NetworkKind.UNMETERED, OfflineNetworkRule.WIFI_ONLY)
        wifi.scheduling.kick()
        runCurrent()
        assertEquals(listOf(false), wifiOnlyMobile.galleryOnlyAsked)
        assertEquals(listOf(true), both.galleryOnlyAsked)
        assertEquals(listOf(true), wifi.galleryOnlyAsked)
    }

    @Test
    fun a_pass_that_throws_does_not_stop_the_next_kick() = runTest {
        val rig = Rig(this)
        var first = true
        val s = ForegroundOfflineScheduling(
            backgroundScope, rig.network, InMemoryOfflineSettings(),
            runPass = { if (first) { first = false; error("disk") } else PassResult(2, 1, 0, 1, false) },
            countWanted = { 1 }, earliestRetryAt = { null }
        )
        s.kick()
        runCurrent()
        s.kick()
        runCurrent()
        assertEquals(false, first)
    }

    @Test
    fun a_background_pass_runs_one_pass_and_schedules_nothing() = runTest {
        val rig = Rig(this, NetworkKind.METERED_OTHER)
        rig.retryAt = 100_000L
        rig.results += PassResult(1, 3, 1, 30, more = true)
        val r = rig.scheduling.runBackgroundPass()
        runCurrent()
        assertEquals(3, r.downloaded)
        assertEquals(true, r.more)
        assertEquals(listOf(NetworkClass.ANY), rig.passes)
        assertEquals(emptyList(), rig.delays)
    }

    @Test
    fun a_background_pass_without_a_network_is_empty() = runTest {
        val rig = Rig(this, NetworkKind.NONE)
        val r = rig.scheduling.runBackgroundPass()
        assertEquals(0, r.downloaded)
        assertEquals(false, r.more)
        assertEquals(emptyList(), rig.passes)
    }
}
