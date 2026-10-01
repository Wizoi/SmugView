package com.smugview.app.diag

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class SinkStatus(val bytesOnDisk: Long, val dropped: Long, val error: String?)

interface LogSink {
    fun append(line: String)
    fun flush(timeoutMs: Long): Boolean
    fun read(maxBytes: Int): String
    fun clear()
    val status: SinkStatus
}

/**
 * Bounded two-file rotating log (`diag-0.log` current, `diag-1.log` previous). All IO happens on
 * one daemon thread; [append] only enqueues, so it is safe on any thread including main. When the
 * queue is full the oldest queued line is dropped and counted.
 */
class FileLogSink(
    val dir: File,
    val maxFileBytes: Long = 512 * 1024,
    val queueCapacity: Int = 2000,
    val openStream: (File) -> OutputStream = { FileOutputStream(it, true) }
) : LogSink {

    private class Marker(val clear: Boolean) {
        val done = CountDownLatch(1)
    }

    private val queue = LinkedBlockingQueue<Any>(queueCapacity)
    private val dropped = AtomicLong(0)
    @Volatile private var error: String? = null
    @Volatile private var disabled = false
    @Volatile private var running = true

    // Touched only by the writer thread.
    private var out: OutputStream? = null
    private var currentBytes = 0L
    private var consecutiveFailures = 0

    private val writer = Thread({ writerLoop() }, "SmugView-diag").apply { isDaemon = true; start() }

    private fun file(n: Int) = File(dir, "diag-$n.log")

    override fun append(line: String) {
        if (disabled) {
            dropped.incrementAndGet()
            return
        }
        enqueue(line)
    }

    private fun enqueue(item: Any) {
        while (!queue.offer(item)) {
            when (val old = queue.poll()) {
                null -> Unit
                is Marker -> old.done.countDown()
                else -> dropped.incrementAndGet()
            }
        }
    }

    override fun flush(timeoutMs: Long): Boolean = awaitMarker(Marker(clear = false), timeoutMs)

    private fun awaitMarker(m: Marker, timeoutMs: Long): Boolean {
        if (!writer.isAlive) return false
        enqueue(m)
        return try {
            m.done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun clear() {
        if (!awaitMarker(Marker(clear = true), 2000)) deleteFiles()
    }

    override fun read(maxBytes: Int): String = try {
        val parts = listOf(file(1), file(0)).filter { it.isFile }
        var total = 0L
        for (f in parts) total += f.length()
        if (total == 0L) "" else {
            val want = minOf(total, maxBytes.toLong()).toInt()
            val buf = ByteArray(want)
            var skip = total - want
            var filled = 0
            for (f in parts) {
                val len = f.length()
                if (skip >= len) { skip -= len; continue }
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(skip)
                    val n = minOf(len - skip, (want - filled).toLong()).toInt()
                    raf.readFully(buf, filled, n)
                    filled += n
                }
                skip = 0
            }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(buf, 0, filled)).toString()
            // A tail read can start mid-line; begin at the next line boundary.
            if (want < total) text.substringAfter('\n', text) else text
        }
    } catch (e: Throwable) {
        "<log unreadable: ${e.javaClass.simpleName}>"
    }

    override val status: SinkStatus
        get() = SinkStatus(
            bytesOnDisk = (file(0).takeIf { it.isFile }?.length() ?: 0L) + (file(1).takeIf { it.isFile }?.length() ?: 0L),
            dropped = dropped.get(),
            error = error
        )

    fun close() {
        running = false
        writer.interrupt()
    }

    private fun writerLoop() {
        val batch = ArrayList<Any>()
        while (running) {
            val first = try {
                queue.poll(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            batch.clear()
            batch.add(first)
            queue.drainTo(batch, 256)
            processBatch(batch)
        }
        closeStream()
    }

    private fun processBatch(batch: List<Any>) {
        for (item in batch) {
            when (item) {
                is String -> if (disabled || !writeLine(item)) dropped.incrementAndGet()
                is Marker -> {
                    if (item.clear) { closeStream(); deleteFiles() } else flushStream()
                    item.done.countDown()
                }
            }
        }
        if (queue.isEmpty()) flushStream()
    }

    /** Returns false if the line could not be written. */
    private fun writeLine(line: String): Boolean {
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            ensureOpen()
            if (currentBytes > 0 && currentBytes + bytes.size > maxFileBytes) rotate()
            out!!.write(bytes)
            currentBytes += bytes.size
            consecutiveFailures = 0
            error = null
            return true
        } catch (e: Throwable) {
            error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
            closeStream()
            if (++consecutiveFailures >= MAX_FAILURES) disabled = true
            return false
        }
    }

    private fun ensureOpen() {
        if (out != null) return
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("not a directory")
        val f = file(0)
        currentBytes = if (f.isFile) f.length() else 0L
        out = BufferedOutputStream(openStream(f), 8192)
    }

    private fun rotate() {
        closeStream()
        file(1).delete()
        file(0).renameTo(file(1))
        ensureOpen()
    }

    private fun flushStream() {
        try { out?.flush() } catch (e: Throwable) {
            error = e.javaClass.simpleName
            closeStream()
        }
    }

    private fun closeStream() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
    }

    private fun deleteFiles() {
        file(0).delete()
        file(1).delete()
        currentBytes = 0
    }

    private companion object {
        const val MAX_FAILURES = 3
    }
}
