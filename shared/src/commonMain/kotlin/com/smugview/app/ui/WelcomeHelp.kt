package com.smugview.app.ui

import com.smugview.app.util.KeyValueStore

/**
 * The welcome help (tester feedback 2026-11-16: "no walkthrough for new users", answered with a short card, not a tour): shown once per
 * install, on the first screen, and always reachable from the Help button. [markShown] is called when it is put on screen, so quitting
 * with it open still counts as seen.
 */
class WelcomeHelp(private val prefs: KeyValueStore) {
    fun shouldShow(): Boolean = prefs.getString(KEY, null) == null

    fun markShown() = prefs.putString(KEY, "1")

    companion object {
        private const val KEY = "welcome_help_shown"
        const val SUPPORT_URL = "https://wizoi.github.io/SmugView/SUPPORT"
        const val PRIVACY_URL = "https://wizoi.github.io/SmugView/PRIVACY_POLICY"
    }
}
