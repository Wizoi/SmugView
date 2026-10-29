package com.smugview.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Play service-account key, the signing keystores and `local.properties` (API key, signing passwords) must never be committed
 * (owner, 2026-10-29). The build reads `play-service-account.json` from the project root, so it sits next to the code: only
 * `.gitignore` keeps it out of git. This fails when a pattern is removed from it.
 */
class SecretsStayIgnoredTest {
    private val ignored = File("../.gitignore").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test fun `the Play service account key is ignored`() = assertIgnored("play-service-account.json")
    @Test fun `local properties is ignored`() = assertIgnored("local.properties")
    @Test fun `keystores are ignored`() { assertIgnored("*.keystore"); assertIgnored("*.jks") }

    private fun assertIgnored(pattern: String) =
        assertTrue("$pattern is missing from .gitignore: ${ignored.sorted()}", pattern in ignored)
}
