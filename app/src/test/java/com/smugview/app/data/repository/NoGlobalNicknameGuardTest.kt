package com.smugview.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R-10 (design 3.5): the repository is a singleton shared by every site, so it must not hold "the
 * current site". Every writer takes the nickname of the work that issued it. Phase 3 step 3-1 made
 * the writers explicit; step 3-11 deleted the global. This keeps it from coming back.
 */
class NoGlobalNicknameGuardTest {
    private val main = File("src/main/java/com/smugview/app")

    private fun isComment(line: String) = line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun hits(dir: File, pattern: Regex): List<String> {
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", files.isNotEmpty())
        return files.flatMap { f ->
            f.readLines().withIndex().filter { !isComment(it.value) && pattern.containsMatchIn(it.value) }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
    }

    @Test fun theRepositoryHoldsNoActiveNickname() {
        val found = hits(File(main, "data/repository"), Regex("""\bactiveNickname\b|\bsetActiveNickname\b"""))
        assertEquals("The repository must not keep a global nickname (R-10):\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun nothingInMainCallsRepositorySetActiveNickname() {
        val found = hits(main, Regex("""\brepository\.setActiveNickname\b"""))
        assertEquals("No caller may set a global nickname on the repository (R-10):\n" + found.joinToString("\n"), emptyList<String>(), found)
    }
}
