package com.smugview.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** The gitignored `local.properties`, read from the module or the repo root. Values are never printed. */
internal object LocalProps {
    private val props: Properties by lazy {
        Properties().also { p ->
            listOf(File("../local.properties"), File("local.properties")).firstOrNull { it.exists() }
                ?.inputStream()?.use { p.load(it) }
        }
    }

    fun get(name: String): String? = props.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Phase 4 step 4-0 (design Q4): fixtures under `api-contract/` are trimmed REAL payloads, committed. They
 * must not carry the API key (shown as `<KEY>`), a `Password=` parameter or a `/Family/` path (the
 * password-folder structure is private). The scan is a pure function so the test can plant a violation
 * and see it caught.
 */
class ApiFixtureGuardTest {
    companion object {
        /** What is wrong with [text], as short messages that never contain the matched secret itself. */
        fun violations(text: String, realKey: String?): List<String> {
            val out = mutableListOf<String>()
            if (!realKey.isNullOrEmpty() && text.contains(realKey)) out += "contains the API key literal"
            if (Regex("APIKey=(?!<KEY>|%3CKEY%3E)", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "APIKey= is not <KEY>"
            if (Regex("\"APIKey\"\\s*:\\s*\"(?!<KEY>\")").containsMatchIn(text)) out += "\"APIKey\" value is not <KEY>"
            if (Regex("Password=", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "contains Password="
            if (text.contains("/Family/") || text.contains("/Family\"")) out += "contains a /Family/ path"
            return out
        }

        private fun fixtureDir(): File {
            val url = ApiFixtureGuardTest::class.java.classLoader!!.getResource("api-contract/accepted-params.json")
                ?: error("api-contract fixtures are not on the test classpath")
            return File(url.toURI()).parentFile
        }
    }

    @Test fun every_committed_fixture_is_clean() {
        val key = LocalProps.get("smugmug.api.key")
        val files = fixtureDir().listFiles { f -> f.isFile }!!.toList()
        assertTrue("no fixtures found", files.size >= 10)
        val bad = files.flatMap { f -> violations(f.readText(), key).map { "${f.name}: $it" } }
        assertEquals("fixture violations: $bad", emptyList<String>(), bad)
    }

    @Test fun a_planted_api_key_is_caught() {
        assertTrue(violations("""{"Uri":"/api/v2/node/x?APIKey=abc123"}""", null).any { it.startsWith("APIKey=") })
        assertTrue(violations("""{"Uri":"/api/v2/node/x?APIKey=<KEY>&count=1"}""", null).isEmpty())
    }

    @Test fun a_planted_key_literal_is_caught_without_printing_it() {
        val v = violations("""{"x":"zzSECRETzz"}""", "zzSECRETzz")
        assertEquals(listOf("contains the API key literal"), v)
    }

    @Test fun a_planted_password_parameter_is_caught() {
        assertTrue(violations("/api/v2/album/x?Password=hunter2", null).any { it.contains("Password=") })
    }

    @Test fun a_planted_family_path_is_caught() {
        assertTrue(violations("""{"WebUri":"https://gallery.example.com/Family/School"}""", null).any { it.contains("/Family/") })
    }

    @Test fun the_scan_covers_a_planted_file_in_a_temp_copy_of_the_directory() {
        val tmp = java.nio.file.Files.createTempDirectory("fixtures").toFile()
        try {
            fixtureDir().listFiles { f -> f.isFile }!!.forEach { it.copyTo(File(tmp, it.name)) }
            File(tmp, "planted.json").writeText("""{"Uri":"/api/v2/x?APIKey=abc&Password=p"}""")
            val bad = tmp.listFiles()!!.flatMap { f -> violations(f.readText(), null).map { "${f.name}: $it" } }
            assertEquals(2, bad.size)
            assertTrue(bad.all { it.startsWith("planted.json") })
        } finally {
            tmp.deleteRecursively()
        }
    }
}
