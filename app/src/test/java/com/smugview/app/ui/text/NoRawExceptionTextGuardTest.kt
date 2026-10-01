package com.smugview.app.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6 (design 3.1, owner rule: every state names its real cause and the way out): an exception's own
 * text (`localizedMessage`, `.message`) never reaches the screen. A screen asks [UserMessages].
 *
 * The allow-list is today's sites in `ui/`, by file and count. It only shrinks: each Phase 6 step that
 * converts a site lowers its count here ((6-3 added this guard; 6-6 removed the search, tag and password sites; 6-7 emptied it: the two download toasts now say [UserMessages.downloadFailed], the cast message says [com.smugview.app.data.repository.AlbumLockedException.userText]). A new site fails the test,
 * and so does a removed one that is still listed, so the list cannot go stale.
 */
class NoRawExceptionTextGuardTest {
    private val ui = File("src/main/java/com/smugview/app/ui")

    /** File name to the number of lines that still read an exception's text or a state's `.message`. */
    private val allowed: Map<String, Int> = emptyMap()

    private fun isComment(line: String) = line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private val reads = Regex("""localizedMessage|\.message\b""")

    @Test fun noNewRawExceptionText_reachesTheScreen_andTheAllowListOnlyShrinks() {
        val files = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", files.size > 20)
        val found = files.filter { it.name != "UserMessages.kt" }.associate { f ->
            f.name to f.readLines().count { !isComment(it) && reads.containsMatchIn(it) }
        }.filterValues { it > 0 }
        assertEquals(
            "Exception text must come from UserMessages, not localizedMessage/.message. Convert the new site, " +
                "or lower the count here if you removed one.",
            allowed.toSortedMap(), found.toSortedMap()
        )
    }
}
