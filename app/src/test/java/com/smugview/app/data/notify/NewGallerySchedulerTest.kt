package com.smugview.app.data.notify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.smugview.app.data.db.AppDatabase
import com.smugview.app.data.db.TestDb
import com.smugview.app.data.offline.InMemoryOfflineSettings
import com.smugview.app.data.offline.OfflineNetworkRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NewGallerySchedulerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var wm: WorkManager
    private val settings = InMemoryOfflineSettings()
    private lateinit var scheduler: NewGalleryScheduler

    @Before fun setUp() {
        db = TestDb.inMemory()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(app)
        scheduler = NewGalleryScheduler({ wm }, db.notifyDao(), settings)
    }

    @After fun tearDown() { db.close() }

    private fun infos(): List<WorkInfo> = wm.getWorkInfosForUniqueWork(NewGalleryScheduler.NAME).get()

    @Test fun `no followed site means no job`() = runBlocking<Unit> {
        scheduler.reschedule()
        assertTrue(infos().isEmpty())
    }

    @Test fun `a followed site gets one periodic job of about six hours that follows the network rule`() = runBlocking<Unit> {
        db.notifyDao().follow("idzifamily")
        settings.set(OfflineNetworkRule.WIFI_AND_MOBILE)
        scheduler.reschedule()
        scheduler.reschedule()
        val job = infos().single()
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(6), job.periodicityInfo!!.repeatIntervalMillis)
        settings.set(OfflineNetworkRule.WIFI_ONLY)
        scheduler.reschedule()
        assertEquals("the rule change replaced the constraint, not a second job", NetworkType.UNMETERED, infos().single().constraints.requiredNetworkType)
    }

    @Test fun `unfollowing the last site cancels the job`() = runBlocking<Unit> {
        db.notifyDao().follow("idzifamily")
        scheduler.reschedule()
        db.notifyDao().unfollow("idzifamily")
        scheduler.reschedule()
        assertTrue(infos().none { it.state != WorkInfo.State.CANCELLED })
    }
}
