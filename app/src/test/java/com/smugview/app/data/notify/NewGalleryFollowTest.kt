package com.smugview.app.data.notify

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.offline.InMemoryOfflineSettings
import com.smugview.app.ui.browser.FollowSiteAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewGalleryFollowTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var wm: WorkManager
    private val events = mutableListOf<String>()
    private var listing = listOf(ListedGallery("n1", "Old", null, null, "k1"))
    private lateinit var follow: NewGalleryFollow

    @Before fun setUp() {
        db = TestDb.inMemory()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(app)
        val check = NewGalleryCheck(db.notifyDao()) { events += "list:$it"; listing }
        follow = NewGalleryFollow(
            db.notifyDao(), check,
            unlock = { events += "unlock:$it" },
            scheduler = NewGalleryScheduler({ wm }, db.notifyDao(), InMemoryOfflineSettings())
        )
    }

    private var bellShown by androidx.compose.runtime.mutableStateOf(true)

    // The bell observes a Room Flow: take it off the screen BEFORE the database closes, or the observer reads a closed pool.
    @After fun tearDown() {
        bellShown = false
        compose.waitForIdle()
        db.close()
    }

    private fun jobs() = wm.getWorkInfosForUniqueWork(NewGalleryScheduler.NAME).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test fun `following unlocks the saved folders before the baseline, so the locked galleries are known and not announced later`() = runBlocking<Unit> {
        follow.follow("idzifamily")
        assertEquals(listOf("unlock:idzifamily", "list:idzifamily"), events)
        assertEquals(CheckResult.NothingNew, NewGalleryCheck(db.notifyDao()) { listing }.run("idzifamily"))
        assertEquals(1, jobs().size)
    }

    @Test fun `following while offline still follows, and the first background run takes the baseline`() = runBlocking<Unit> {
        val offline = NewGalleryCheck(db.notifyDao()) { throw java.io.IOException("offline") }
        NewGalleryFollow(db.notifyDao(), offline, { }, NewGalleryScheduler({ wm }, db.notifyDao(), InMemoryOfflineSettings())).follow("idzifamily")
        assertTrue(db.notifyDao().site("idzifamily")!!.enabled)
        assertFalse(db.notifyDao().site("idzifamily")!!.baselineDone)
        assertEquals(CheckResult.Baselined(1), NewGalleryCheck(db.notifyDao()) { listing }.run("idzifamily"))
    }

    @Test fun `unfollowing forgets the site and cancels the job`() = runBlocking<Unit> {
        follow.follow("idzifamily")
        follow.unfollow("idzifamily")
        assertTrue(db.notifyDao().site("idzifamily") == null)
        assertTrue(jobs().isEmpty())
    }

    @Test fun `the permission rule - ask only when not following and not allowed`() {
        assertEquals(FollowTap.Unfollow, FollowTap.of(following = true, canNotify = false))
        assertEquals(FollowTap.AskPermission, FollowTap.of(following = false, canNotify = false))
        assertEquals(FollowTap.Follow, FollowTap.of(following = false, canNotify = true))
    }

    @Test fun `the bell follows and unfollows a site once notifications are allowed`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent { if (bellShown) FollowSiteAction(follow, "idzifamily") }
        compose.onNodeWithContentDescription(NewGalleryText.FOLLOW_DESCRIPTION).performClick()
        compose.waitUntil(10_000) { runBlocking { db.notifyDao().site("idzifamily") } != null }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithDescription(NewGalleryText.UNFOLLOW_DESCRIPTION)
        }
        compose.onNodeWithContentDescription(NewGalleryText.UNFOLLOW_DESCRIPTION).performClick()
        compose.waitUntil(10_000) { runBlocking { db.notifyDao().site("idzifamily") } == null }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithDescription(d: String) =
        onAllNodesWithContentDescription(d).fetchSemanticsNodes().isNotEmpty()
}
