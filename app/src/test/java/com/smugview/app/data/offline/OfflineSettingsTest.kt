package com.smugview.app.data.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OfflineSettingsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun prefs() = app.getSharedPreferences("test_" + System.nanoTime(), 0)

    @Test fun theDefaultIsWifiOnly() {
        assertEquals(OfflineNetworkRule.WIFI_ONLY, PrefsOfflineSettings(prefs(), Dispatchers.Unconfined).rule.value)
    }

    @Test fun theChoiceSurvivesANewInstance_andTheFlowEmits() = runBlocking {
        val p = prefs()
        val first = PrefsOfflineSettings(p, Dispatchers.Unconfined)

        first.set(OfflineNetworkRule.WIFI_AND_MOBILE)

        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, first.rule.value)
        assertEquals(OfflineNetworkRule.WIFI_AND_MOBILE, PrefsOfflineSettings(p, Dispatchers.Unconfined).rule.value)
        first.set(OfflineNetworkRule.WIFI_ONLY)
        assertEquals(OfflineNetworkRule.WIFI_ONLY, PrefsOfflineSettings(p, Dispatchers.Unconfined).rule.value)
    }

    @Test fun anUnknownStoredValueReadsAsWifiOnly() {
        val p = prefs()
        p.edit().putString(PrefsOfflineSettings.KEY, "EVERYTHING").commit()

        assertEquals(OfflineNetworkRule.WIFI_ONLY, PrefsOfflineSettings(p, Dispatchers.Unconfined).rule.value)
    }
}
