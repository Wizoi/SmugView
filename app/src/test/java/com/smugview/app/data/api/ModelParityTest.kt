package com.smugview.app.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * iOS spike (Stage 0, step 2): what would a strict JSON reader do with real SmugMug answers?
 *
 * Gson fills a field the answer lacks with null, even in a non-null Kotlin property, and carries on. kotlinx.serialization (what Ktor
 * uses, and the only choice on iOS) throws for a property that is missing and has no default, and fills the default for one that has
 * one. So, over the answers [ParityRecorder] saved, this counts the two cases:
 *  - THROW: non-null, no default, null after Gson: kotlinx would throw (the model must become nullable or get a default first);
 *  - DEFAULT: non-null, has a default, null after Gson: today's code can hit a null here; kotlinx would apply the default.
 * Limits: it sees only what the saved answers show (a field absent from all of them is not reported), and Gson's leniency about
 * types (a number in quotes, a string where an object is expected) is not covered.
 */
class ModelParityTest {
    private val dir = File("src/test/resources/parity")
    private val gson = Gson()

    private fun responseClass(method: String): KClass<*> =
        SmugMugApi::class.declaredFunctions.first { it.name == method }.returnType.classifier as KClass<*>

    private fun walk(obj: Any?, path: String, throws: MutableList<String>, defaults: MutableList<String>) {
        when (obj) {
            null -> return
            is List<*> -> obj.forEach { walk(it, path, throws, defaults) }
            is Map<*, *> -> obj.values.forEach { walk(it, path, throws, defaults) }
            else -> {
                val cls = obj::class
                if (!cls.java.name.startsWith("com.smugview.app.data.api")) return
                val optional = cls.primaryConstructor?.parameters?.filter { it.isOptional }?.mapNotNull { it.name }?.toSet().orEmpty()
                for (p in cls.memberProperties) {
                    @Suppress("UNCHECKED_CAST") val prop = p as KProperty1<Any, Any?>
                    if (prop.name !in (cls.primaryConstructor?.parameters?.mapNotNull { it.name } ?: emptyList())) continue
                    prop.isAccessible = true
                    val value = prop.get(obj)
                    if (value == null && !prop.returnType.isMarkedNullable) {
                        (if (prop.name in optional) defaults else throws) += "${cls.simpleName}.${prop.name}"
                    } else walk(value, "$path.${prop.name}", throws, defaults)
                }
            }
        }
    }

    @Test fun `every saved real answer parses, and the strictness report is written`() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("run ParityRecorder first: ${dir.absolutePath}", files.isNotEmpty())
        val throwCount = sortedMapOf<String, Int>()
        val defaultCount = sortedMapOf<String, Int>()
        val lines = mutableListOf<String>()
        for (f in files) {
            val method = f.name.substringBefore("__")
            val parsed = gson.fromJson(f.readText(), responseClass(method).java)
            val throws = mutableListOf<String>(); val defaults = mutableListOf<String>()
            walk(parsed, "", throws, defaults)
            throws.forEach { throwCount.merge(it, 1, Int::plus) }
            defaults.forEach { defaultCount.merge(it, 1, Int::plus) }
            lines += "${f.name}: throw=${throws.toSet().size} default=${defaults.toSet().size}"
        }
        val report = buildString {
            appendLine("files: ${files.size}")
            appendLine("== THROW (non-null, no default, missing in the answer): ${throwCount.size} properties")
            throwCount.forEach { (k, v) -> appendLine("  $k  x$v") }
            appendLine("== DEFAULT (non-null with a default, missing in the answer): ${defaultCount.size} properties")
            defaultCount.forEach { (k, v) -> appendLine("  $k  x$v") }
            appendLine("== per file"); lines.forEach { appendLine("  $it") }
        }
        File("build").mkdirs(); File("build/parity-report.txt").writeText(report)
        println(report)
        assertEquals("every file was read", files.size, lines.size)
    }
}
