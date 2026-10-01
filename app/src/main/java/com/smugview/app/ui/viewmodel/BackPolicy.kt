package com.smugview.app.ui.viewmodel

/**
 * When the in-app system-Back handler is enabled (R-14, design 3.2, owner Q2). Pure so it can be
 * tabled in a test: the Composables only pass in what they see.
 */
object BackPolicy {
    /**
     * Folders: pop the folder stack, or return to Search after a search jump. Collections: close the
     * open collection. Every other tab: not handled here, so Android's default applies (the app goes
     * to the background). The folder stack is invisible off the Folders tab, so it never counts there.
     */
    fun enabled(tab: BrowserTab, stackSize: Int, returnToSearch: Boolean, openCollection: Boolean): Boolean =
        when (tab) {
            BrowserTab.Folders -> stackSize > 0 || returnToSearch
            BrowserTab.Collections -> openCollection
            BrowserTab.Hub, BrowserTab.Search, BrowserTab.TagSearch -> false
        }
}
