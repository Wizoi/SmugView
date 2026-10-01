package com.smugview.app.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FileLogSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sinks = mutableListOf<FileLogSink>()

    @After fun tearDown() { sinks.forEach { it.close() } }

    private fun sink(
        dir: File = tmp.newFolder(),
        maxFileBytes: Long = 512 * 1024,
        capacity: Int = 2000,
        open: (File) -> OutputStream = { FileOutputStream(it, true) }
    ): FileLogSink = FileLogSink(dir, maxFileBytes, capacity, open).also { sinks.add(it) }

    private fun log(sink: LogSink, secrets: List<String> = emptyList(), ring: Int = 2000): DiagLog {
        val redactor = Redactor().also { it.setSecrets(secrets) }
        return DiagLog(sink, redactor, clock = { 1_700_000_000_000L }, ringSize = ring)
    }

    @Test fun rotation_keepsAtMostTwoFiles_andTheNewestLines() {
        val dir = tmp.newFolder()
        val s = sink(dir, maxFileBytes = 1000)
        val l = log(s)
        repeat(300) { l.i("t", "line number $it padded to a reasonable length") }
        assertTrue(l.flush(5000))
        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("diag-0.log", "diag-1.log"), files)
        for (f in dir.listFiles()!!) assertTrue("${f.name} is ${f.length()}", f.length() <= 1000)
        val text = s.read(Int.MAX_VALUE)
        assertTrue(text.contains("line number 299 "))
        assertFalse(text.contains("line number 0 "))
    }

    @Test fun eightThreads_everyLineIsPresentOrCountedDropped() {
        val s = sink()
        val l = log(s)
        val threads = (0 until 8).map { t ->
            thread { repeat(1000) { l.i("c", "t$t-n$it") } }
        }
        threads.forEach { it.join() }
        assertTrue(l.flush(10_000))
        val text = s.read(Int.MAX_VALUE)
        val present = Regex("""c t\d-n\d+""").findAll(text).count()
        assertEquals(8000, present + s.status.dropped.toInt())
        assertTrue("nothing written", present > 0)
    }

    @Test fun ioFailure_doesNotThrow_setsError_andRingStillWorks() {
        val notADir = tmp.newFile("blocker")
        val s = sink(dir = notADir)
        val l = log(s)
        l.e("t", "first")
        l.e("t", "second")
        assertTrue(l.flush(5000))
        assertNotNull(s.status.error)
        assertEquals(2, l.recent(10).size)
        assertTrue(l.recent(10).last().endsWith("second"))
        assertEquals("", s.read(1000))
    }

    @Test fun sinkIsDisabledAfterRepeatedFailures_butNeverThrows() {
        val s = sink(dir = tmp.newFile("blocker2"))
        repeat(5) { s.append("line $it"); s.flush(2000) }
        s.append("after")
        assertNotNull(s.status.error)
        assertTrue(s.status.dropped >= 1)
    }

    @Test fun corruptedFile_readReturnsTextAndLoggingContinues() {
        val dir = tmp.newFolder()
        File(dir, "diag-0.log").writeBytes(ByteArray(2000) { (it * 31 + 7).toByte() })
        val s = sink(dir)
        val l = log(s)
        l.i("t", "survivor")
        assertTrue(l.flush(5000))
        val text = s.read(Int.MAX_VALUE)
        assertTrue(text.contains("survivor"))
    }

    @Test fun emptyOrMissingDir_readsEmpty() {
        assertEquals("", sink().read(1000))
        assertEquals("", sink(File(tmp.root, "does/not/exist")).read(1000))
    }

    @Test fun secretsAreRedactedBeforeTheyReachDisk() {
        val dir = tmp.newFolder()
        val s = sink(dir)
        val l = log(s, secrets = listOf("TESTKEY123abc"))
        l.i("http", "GET /x?APIKey=TESTKEY123abc failed for hunter2pass", RuntimeException("boom TESTKEY123abc"))
        assertTrue(l.flush(5000))
        val raw = dir.listFiles()!!.joinToString("") { it.readText() }
        assertTrue(raw.contains("GET /x"))
        assertFalse(raw, raw.contains("TESTKEY123abc"))
        assertFalse(l.recent(5).joinToString("\n").contains("TESTKEY123abc"))
        assertFalse(l.readPersisted(10_000).contains("TESTKEY123abc"))
    }

    @Test fun fileWrites_neverHappenOnTheCallerThread() {
        val names = Collections.synchronizedSet(mutableSetOf<String>())
        val s = sink(open = { f ->
            object : FileOutputStream(f, true) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    names.add(Thread.currentThread().name)
                    super.write(b, off, len)
                }
            }
        })
        val l = log(s)
        repeat(50) { l.i("t", "line $it") }
        assertTrue(l.flush(5000))
        assertEquals(setOf("SmugView-diag"), names.toSet())
        assertTrue(Thread.currentThread().name !in names)
    }

    @Test fun fullQueue_dropsTheOldestAndCountsThem() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = sink(capacity = 3, open = { f ->
            object : FileOutputStream(f, true) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    super.write(b, off, len)
                }
            }
        })
        s.append("first")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(10) { s.append("l$it") }
        release.countDown()
        assertTrue(s.flush(5000))
        val text = s.read(Int.MAX_VALUE)
        assertTrue(text.contains("first"))
        assertTrue(text.contains("l9"))
        assertFalse(text.contains("l0"))
        // 7 lines overflowed; the flush marker may evict one more queued line.
        assertTrue("dropped=${s.status.dropped}", s.status.dropped in 7L..8L)
    }

    @Test fun clear_deletesFilesAndLoggingResumes() {
        val dir = tmp.newFolder()
        val s = sink(dir)
        val l = log(s)
        l.i("t", "before")
        assertTrue(l.flush(5000))
        l.clear()
        assertEquals("", s.read(1000))
        l.i("t", "after")
        assertTrue(l.flush(5000))
        val text = s.read(1000)
        assertTrue(text.contains("after"))
        assertFalse(text.contains("before"))
    }

    @Test fun lineFormat_hasTimestampLevelCategory() {
        val l = log(sink())
        l.w("sync", "hello")
        val line = l.recent(1).single()
        assertTrue(line, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3} W sync hello""").matches(line))
    }

    @Test fun recent_filtersByLevelAndKeepsTheNewest() {
        val l = log(sink(), ring = 3)
        l.i("t", "a"); l.e("t", "b"); l.i("t", "c"); l.w("t", "d")
        assertEquals(listOf("c", "d"), l.recent(10).map { it.substringAfterLast(' ') }.takeLast(2))
        assertEquals(3, l.recent(10).size)
        assertEquals(listOf("d"), l.recent(10, Level.W).map { it.substringAfterLast(' ') }.takeLast(1))
        assertEquals(2, l.recent(10, Level.W).size)
    }

    @Test fun callerCost_isUnder200MicrosPerLine() {
        val l = log(sink(), secrets = listOf("TESTKEY123abc", "hunter22xyz"))
        val line = "[folder#3] GET /api/v2/node/2sDN5x!children?start=1&count=100&APIKey=TESTKEY123abc -> 200 network 123ms tries=1"
        repeat(500) { l.i("http", line) } // warm up
        val start = System.nanoTime()
        repeat(10_000) { l.i("http", line) }
        val perLineMicros = (System.nanoTime() - start) / 10_000 / 1000
        assertTrue("avg ${perLineMicros}us per line", perLineMicros < 200)
    }

    @Test fun status_reportsBytesAndNoError() {
        val s = sink()
        val l = log(s)
        l.i("t", "x")
        assertTrue(l.flush(5000))
        assertTrue(l.status().bytesOnDisk > 0)
        assertNull(l.status().error)
    }
}
