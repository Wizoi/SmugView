package com.smugview.app.ui

import com.smugview.app.data.offline.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tester feedback (2026-11-16): new users get no guidance. The welcome help shows once per install, never again after it was shown. */
class WelcomeHelpTest {
    @Test fun aNewInstallShowsTheWelcomeOnce() {
        val prefs = FakeKeyValueStore()
        val help = WelcomeHelp(prefs)
        assertTrue(help.shouldShow())
        help.markShown()
        assertFalse(help.shouldShow())
    }

    @Test fun theChoiceSurvivesANewInstanceOverTheSameStore() {
        val prefs = FakeKeyValueStore()
        WelcomeHelp(prefs).markShown()
        assertFalse(WelcomeHelp(prefs).shouldShow(), "a second launch does not show it again")
    }

    @Test fun theLinksAreTheSupportAndPrivacyPages() {
        assertEquals("https://wizoi.github.io/SmugView/SUPPORT", WelcomeHelp.SUPPORT_URL)
        assertEquals("https://wizoi.github.io/SmugView/PRIVACY_POLICY", WelcomeHelp.PRIVACY_URL)
    }
}
