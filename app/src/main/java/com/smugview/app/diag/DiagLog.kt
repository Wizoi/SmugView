package com.smugview.app.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Level { D, I, W, E }

typealias LogStatus = SinkStatus

/**
 * Redacting, bounded log. Never throws. Redaction and the ring append happen on the caller thread
 * (microseconds); disk IO is the sink's writer thread.
 */
class DiagLog(
    private val sink: LogSink,
    private val redactor: Redactor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ringSize: Int = 2000,
    private val logcat: ((Level, String, String) -> Unit)? = null
) {
    private class Entry(val level: Level, val text: String)

    private val ring = ArrayDeque<Entry>()

    fun log(level: Level, cat: String, msg: String, t: Throwable? = null) {
        runCatching {
            val body = if (t == null) msg else msg + "\n" + t.stackTraceToString()
            val safe = redactor.redact(body, if (t == null) LINE_CAP else TRACE_CAP)
            val line = TS.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault())) +
                " " + level + " " + cat + " " + safe
            if (ringSize > 0) synchronized(ring) {
                ring.addLast(Entry(level, line))
                while (ring.size > ringSize) ring.removeFirst()
            }
            sink.append(line)
            if (level >= Level.W) logcat?.invoke(level, cat, safe)
        }
    }

    fun d(cat: String, msg: String, t: Throwable? = null) = log(Level.D, cat, msg, t)
    fun i(cat: String, msg: String, t: Throwable? = null) = log(Level.I, cat, msg, t)
    fun w(cat: String, msg: String, t: Throwable? = null) = log(Level.W, cat, msg, t)
    fun e(cat: String, msg: String, t: Throwable? = null) = log(Level.E, cat, msg, t)

    /** The newest [n] in-memory entries at or above [minLevel], oldest first. */
    fun recent(n: Int, minLevel: Level = Level.D): List<String> = synchronized(ring) {
        ring.filter { it.level >= minLevel }.takeLast(n).map { it.text }
    }

    fun readPersisted(maxBytes: Int): String = runCatching { sink.read(maxBytes) }.getOrDefault("")

    fun flush(timeoutMs: Long): Boolean = runCatching { sink.flush(timeoutMs) }.getOrDefault(false)

    fun clear() {
        synchronized(ring) { ring.clear() }
        runCatching { sink.clear() }
    }

    fun status(): LogStatus = runCatching { sink.status }.getOrDefault(SinkStatus(0, 0, "status unavailable"))

    companion object {
        private const val LINE_CAP = 4096
        private const val TRACE_CAP = 8192
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

        val NOOP = DiagLog(object : LogSink {
            override fun append(line: String) {}
            override fun flush(timeoutMs: Long) = true
            override fun read(maxBytes: Int) = ""
            override fun clear() {}
            override val status = SinkStatus(0, 0, null)
        }, Redactor(), ringSize = 0)
    }
}

/** Static holder so `SmugLog` and the crash handler can reach the log without injection. */
object Diag {
    @Volatile var log: DiagLog = DiagLog.NOOP

    /** Exceptions that reached a site session's handler (6-6): a job nobody caught. Counted in `report.txt`, never shown. */
    val uncaughtSiteErrors = java.util.concurrent.atomic.AtomicInteger()
}
