package com.smugview.app.diag

/** Records every appended line in memory; for tests that only care what reached the sink. */
class RecordingSink : LogSink {
    val lines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var flushResult = true
    override fun append(line: String) { lines.add(line) }
    override fun flush(timeoutMs: Long) = flushResult
    override fun read(maxBytes: Int) = lines.joinToString("\n")
    override fun clear() { lines.clear() }
    override val status get() = SinkStatus(0, 0, null)
}
