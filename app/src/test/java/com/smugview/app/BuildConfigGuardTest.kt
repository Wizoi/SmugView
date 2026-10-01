package com.smugview.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R-51: release stack traces must be readable once deobfuscated. Without these attributes R8 drops
 * line numbers, so a crash line in the diagnostics log can't be traced to a source line.
 */
class BuildConfigGuardTest {
    private fun rules() = File("proguard-rules.pro").readText()

    @Test
    fun proguard_keepsSourceFileAndLineNumbers() {
        val text = rules().lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertTrue(text.contains(Regex("""-keepattributes\s+SourceFile\s*,\s*LineNumberTable""")))
        assertTrue(text.contains(Regex("""-renamesourcefileattribute\s+SourceFile""")))
    }

    @Test
    fun releaseMappingsAreNeverCommitted() {
        val ignore = File("../.gitignore").readText().lines().map { it.trim() }
        assertTrue(ignore.contains("release-mappings/"))
    }

    @Test
    fun buildScriptArchivesTheReleaseMapping() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("archiveReleaseMapping"))
        assertTrue(gradle.contains("release-mappings"))
    }
}
