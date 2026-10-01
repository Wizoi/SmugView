package com.smugview.app.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps the last [MAX_RUNS] sync/unlock runs in memory and appends them to [file] as JSON lines:
 * `start` when a run begins, `end` when it finishes, `amend` for the post-sync numbers. A `start`
 * with no `end` reads back as [StopReason.Interrupted] (the process died mid-run).
 *
 * Disk IO happens on one daemon thread, never on the caller. Every method swallows its own
 * failures: a full disk or a corrupt file must never affect a sync.
 *
 * [postSync] runs on [scope] (not the sync's own scope) after a [SyncKind.GallerySync] finishes, so it
 * adds no latency to the sync and survives its cancellation.
 */
class FileSyncReporter(
    private val file: File,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val postSync: (suspend (SyncRun) -> Unit)? = null,
    private val scope: CoroutineScope? = null
) : SyncReporter {

    private val lock = Any()
    private val history = LinkedHashMap<String, SyncRun>()
    private val open = ConcurrentHashMap<String, SyncRun>()

    private val writer = ThreadPoolExecutor(
        0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()
    ) { r -> Thread(r, "SmugView-sync-report").apply { isDaemon = true } }

    init {
        runCatching { load() }
        // Compact an oversized file once at startup, off the caller thread.
        runCatching { if (file.length() > MAX_FILE_BYTES) writer.execute { compact() } }
    }

    override fun begin(kind: SyncKind, nickname: String, actionId: String): SyncRun? = runCatching {
        val run = SyncRun(actionId, kind, nickname, clock())
        synchronized(lock) { remember(run) }
        open[actionId] = run
        enqueue("start", run)
        log.i("sync", "begin $actionId $kind nick=$nickname")
        run
    }.getOrNull()

    override fun finish(run: SyncRun) {
        runCatching {
            run.endedAt = clock()
            if (run.stop == null) run.stop = StopReason.Completed
            open.remove(run.runId)
            enqueue("end", run)
            log.i("sync", "end ${run.summary().lineSequence().first()}")
            val post = postSync
            val sc = scope
            if (run.kind == SyncKind.GallerySync && post != null && sc != null) {
                sc.launch {
                    runCatching { post(run) }
                        .onFailure { log.w("sync", "post-sync numbers failed: ${it.javaClass.simpleName}") }
                    runCatching { enqueueAmend(run) }
                }
            }
        }
    }

    override fun recordUnlock(a: UnlockAttempt) {
        runCatching {
            a.actionId?.let { open[it] }?.unlocks?.add(a)
            val msg = "unlock via=${a.via} target=${a.target} result=${a.result}" +
                (a.httpCode?.let { " http=$it" } ?: "") + (a.exception?.let { " ex=$it" } ?: "") +
                " ${a.ms}ms action=${a.actionId ?: "-"}"
            if (a.result == "Success") log.i("sync", msg) else log.w("sync", msg)
        }
    }

    override fun recent(n: Int): List<SyncRun> = synchronized(lock) { history.values.toList() }.takeLast(n)

    override fun hasOpenRun(): Boolean = open.isNotEmpty()

    /** Waits until everything queued so far has been written. For tests and for the report writer. */
    fun flush(timeoutMs: Long): Boolean = runCatching {
        writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    private fun remember(run: SyncRun) {
        history[run.runId] = run
        while (history.size > MAX_RUNS) history.remove(history.keys.first())
    }

    private fun enqueue(type: String, run: SyncRun) {
        val line = JSONObject().put("t", type).put("run", run.toJson()).toString()
        writeLine(line)
    }

    private fun enqueueAmend(run: SyncRun) {
        val o = JSONObject().put("t", "amend").put("runId", run.runId)
        run.newInIndex30d?.let { o.put("newInIndex30d", it) }
        run.litDotNodes?.let { o.put("litDotNodes", it) }
        writeLine(o.toString())
    }

    private fun writeLine(line: String) {
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                if (file.length() > MAX_FILE_BYTES) compact()
            }
        }
    }

    /** Rewrites the file from the in-memory history (one line per run). Runs on the writer thread. */
    private fun compact() {
        runCatching {
            val runs = synchronized(lock) { history.values.toList() }
            val text = runs.joinToString("") { r ->
                JSONObject().put("t", if (r.endedAt != null) "end" else "start").put("run", r.toJson()).toString() + "\n"
            }
            file.writeText(text)
        }
    }

    private fun load() {
        if (!file.isFile) return
        var skipped = 0
        file.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                try {
                    val o = JSONObject(line)
                    when (o.optString("t")) {
                        "start", "end" -> {
                            val run = SyncRun.fromJson(o.getJSONObject("run"))
                            synchronized(lock) { history.remove(run.runId); remember(run) }
                        }
                        "amend" -> synchronized(lock) {
                            history[o.getString("runId")]?.let { r ->
                                if (o.has("newInIndex30d")) r.newInIndex30d = o.getInt("newInIndex30d")
                                if (o.has("litDotNodes")) r.litDotNodes = o.getInt("litDotNodes")
                            }
                        }
                        else -> skipped++
                    }
                } catch (e: Exception) {
                    skipped++
                }
            }
        }
        synchronized(lock) {
            for (r in history.values) {
                if (r.endedAt == null && r.stop == null) r.stop = StopReason.Interrupted
            }
        }
        if (skipped > 0) log.w("sync", "sync report file: skipped $skipped unreadable lines")
    }

    private companion object {
        const val MAX_RUNS = 20
        const val MAX_FILE_BYTES = 256L * 1024
    }
}
