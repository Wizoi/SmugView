package com.smugview.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review R-01: `Uris.ParentNode` is the node's own `!parent` link, never its parent's ID. Phase 2
 * (T1) removed every parser of it; a parent comes only from a listing, `Uris.Folder`/`UrlPath`, or
 * `node/{id}!parents`. This keeps a new parser from creeping back in.
 */
class NoParentNodeParsersGuardTest {
    private val main = File("src/main/java/com/smugview/app")

    private fun isComment(line: String) = line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun sources() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { it.name != "ResponseModels.kt" }.toList()

    @Test fun noUrisParentNodeReads_outsideResponseModels() {
        val files = sources()
        assertTrue("source tree not found from ${File(".").absolutePath}", files.size > 20)
        val hits = files.flatMap { f ->
            f.readLines().withIndex().filter { !isComment(it.value) && Regex("""uris\??\.parentNode\b""").containsMatchIn(it.value) }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals("Uris.ParentNode must not be read (R-01):\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test fun parentNode_isNotRequestedInAnyFilterUri() {
        val hits = sources().flatMap { f ->
            f.readLines().withIndex().filter { !isComment(it.value) && Regex("""\bParentNode\b""").containsMatchIn(it.value) }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals("ParentNode must not be asked for in _filteruri:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }
}
