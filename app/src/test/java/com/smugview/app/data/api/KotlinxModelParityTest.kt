package com.smugview.app.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.full.declaredFunctions

/**
 * iOS port, Stage 1c gate: the app's own response models read every real SmugMug answer recorded by [ParityRecorder] with
 * kotlinx.serialization (what Ktor uses, and the only reader on iOS), which throws where Gson used to carry on with a null.
 * The recordings are of the owner's public site (23 answers, one per `SmugMugApi` call the app makes).
 */
class KotlinxModelParityTest {
    private val dir = File("src/test/resources/parity")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test fun `every recorded real answer decodes into the app's models`() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("run ParityRecorder first: ${dir.absolutePath}", files.isNotEmpty())
        val failures = mutableListOf<String>()
        for (f in files) {
            val method = f.name.substringBefore("__")
            val type = SmugMugApi::class.declaredFunctions.first { it.name == method }.returnType
            try {
                json.decodeFromString(serializer(type), f.readText())
            } catch (e: Exception) {
                failures += "${f.name}: ${e.message?.lineSequence()?.first()?.take(160)}"
            }
        }
        assertEquals("answers the models cannot read:\n" + failures.joinToString("\n"), 0, failures.size)
    }
}
