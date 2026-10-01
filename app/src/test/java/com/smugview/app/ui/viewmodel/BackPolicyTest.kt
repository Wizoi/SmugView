package com.smugview.app.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** R-14 / owner Q2: system Back is in-app only where it has something visible to undo. */
class BackPolicyTest {
    private data class Case(
        val name: String,
        val tab: BrowserTab,
        val stack: Int,
        val returnToSearch: Boolean,
        val openCollection: Boolean,
        val enabled: Boolean
    )

    private val table = listOf(
        // The folder stack is invisible off the Folders tab: Back must leave the app there.
        Case("Home, stack of 2", BrowserTab.Hub, 2, false, false, false),
        Case("Search, stack of 2", BrowserTab.Search, 2, false, false, false),
        Case("Tags, stack of 2", BrowserTab.TagSearch, 2, false, false, false),
        Case("Collections list, stack of 2", BrowserTab.Collections, 2, false, false, false),
        Case("Home with a search jump pending", BrowserTab.Hub, 0, true, false, false),
        // Collections: Back closes an open collection, and only that.
        Case("Collections, collection open", BrowserTab.Collections, 0, false, true, true),
        Case("Collections, collection open and stack of 2", BrowserTab.Collections, 2, false, true, true),
        Case("Collections, nothing open", BrowserTab.Collections, 0, false, false, false),
        // Folders: pop the stack, or return to Search after a search jump.
        Case("Folders, stack empty, no search jump", BrowserTab.Folders, 0, false, false, false),
        Case("Folders, stack of 1", BrowserTab.Folders, 1, false, false, true),
        Case("Folders, stack of 2", BrowserTab.Folders, 2, false, false, true),
        Case("Folders, returnToSearch", BrowserTab.Folders, 0, true, false, true),
        Case("Folders, returnToSearch and stack", BrowserTab.Folders, 2, true, false, true),
        // A collection flag means nothing on the Folders tab.
        Case("Folders, stack empty, openCollection set", BrowserTab.Folders, 0, false, true, false)
    )

    @Test fun backPolicyTable() {
        val wrong = table.filter { BackPolicy.enabled(it.tab, it.stack, it.returnToSearch, it.openCollection) != it.enabled }
            .map { "${it.name}: expected ${it.enabled}" }
        assertEquals("Rows where BackPolicy.enabled is wrong:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test fun everyTabIsCoveredByTheTable() {
        assertEquals(BrowserTab.values().toSet(), table.map { it.tab }.toSet())
    }
}
