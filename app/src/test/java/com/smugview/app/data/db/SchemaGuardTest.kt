package com.smugview.app.data.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * File-based guards for the Room migration workflow (R-67). Changing an entity must come with an
 * exported schema JSON and a registered migration; these fail loudly when one is forgotten.
 */
class SchemaGuardTest {
    private val schemas = File("schemas/com.smugview.app.data.db.AppDatabase")
    private val main = File("src/main/java/com/smugview/app")

    private fun declaredVersion(): Int {
        val src = File(main, "data/db/AppDatabase.kt").readText()
        return Regex("""version\s*=\s*(\d+)""").find(src)!!.groupValues[1].toInt()
    }

    @Test
    fun exportedSchemaExists_forCurrentDatabaseVersion() {
        val v = declaredVersion()
        assertTrue("schemas/.../$v.json is missing: build once and commit it", File(schemas, "$v.json").isFile)
    }

    @Test
    fun everyMigrationDefinedInAppDatabase_isRegisteredInAppModule() {
        val db = File(main, "data/db/AppDatabase.kt").readText()
        val module = File(main, "di/AppModule.kt").readText()
        val defined = Regex("""val (MIGRATION_\d+_\d+)\s*=""").findAll(db).map { it.groupValues[1] }.toList()
        assertTrue("no migrations found, the regex is stale", defined.isNotEmpty())
        for (m in defined) {
            assertTrue("$m is not passed to addMigrations in AppModule", module.contains("AppDatabase.$m"))
        }
    }

    @Test
    fun migrationChain_reachesCurrentVersion() {
        val db = File(main, "data/db/AppDatabase.kt").readText()
        val ends = Regex("""MIGRATION_\d+_(\d+)\s*=""").findAll(db).map { it.groupValues[1].toInt() }.toList()
        assertTrue("no migration ends at version ${declaredVersion()}", declaredVersion() in ends)
    }
}
