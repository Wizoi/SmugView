package com.smugview.app.ui

/**
 * Store-screenshot hooks for the iOS CI job (`.github/workflows/ios-store-screenshots.yml`): the simulator cannot tap, so the job asks the
 * app to open one screen state by itself. Every field is null in normal use and on Android; only `IosApp.kt` ever sets them, from
 * `SMUGVIEW_DEMO_*` environment variables.
 */
object DemoHooks {
    /** A navigation route to open a few seconds after start, e.g. `photo_grid/nMmnCR/Norway`. */
    var route: String? = null

    /** `info` or `share`: the photo viewer shows its controls and opens that sheet/dialog once. */
    var sheet: String? = null
        private set

    private var sheetPending = false

    fun setSheet(value: String?) {
        sheet = value?.trim()?.lowercase()?.takeIf { it == "info" || it == "share" }
        sheetPending = sheet != null
    }

    /** True while [kind] is requested and not yet shown. Does not consume it, so an effect cancelled during its delay can start again. */
    fun wants(kind: String): Boolean = sheetPending && sheet == kind

    /** True once, for the first screen that asks for [kind]; false afterwards and whenever no hook is set. */
    fun takeSheet(kind: String): Boolean {
        if (!sheetPending || sheet != kind) return false
        sheetPending = false
        return true
    }
}
