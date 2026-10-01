package com.smugview.app.diag

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** What [DiagnosticsFileWriter.write] did. [Blocked] still leaves a short placeholder in the file. */
sealed interface ReportResult {
    data class Written(val file: File, val bytes: Int) : ReportResult
    data class Blocked(val reason: String) : ReportResult
    data class Failed(val reason: String) : ReportResult
}

/**
 * Writes `report.txt` (doctor counts, the last sync runs, HTTP stats, the redacted log tail) into the
 * app's external files directory, where `adb pull` can reach it (design §0 Q2: no screen, no share
 * sheet). Nothing here leaves the device on its own.
 *
 * Privacy (design §3.4): the whole text is passed through the redactor a second time with the
 * *current* secrets, then scanned. If an API key or a saved password (4+ chars) or a credential
 * pattern survives, the report is **replaced** by a short notice, an E line without the secret is
 * logged, and nothing else is written.
 *
 * Size: the log tail is trimmed from its oldest end so the file never exceeds [maxBytes].
 * The writer never throws; [write] returns what happened. It does blocking IO: call it off the main thread.
 */
class DiagnosticsFileWriter(
    private val dirProvider: () -> File?,
    private val log: DiagLog,
    private val redactor: Redactor,
    private val reporter: SyncReporter,
    private val secrets: () -> Collection<String>,
    private val doctor: CacheDoctor? = null,
    private val header: () -> String = { "" },
    private val httpStats: () -> HttpStats? = { null },
    private val maxBytes: Int = MAX_BYTES,
    /** The second redaction pass over the whole text. Replaceable so a test can simulate a broken one. */
    private val finalPass: (String) -> String = { text ->
        text.split('\n').joinToString("\n") { redactor.redact(it) }
    },
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile private var lastDoctor: DoctorReport? = null
    private val doctorRanThisLaunch = AtomicBoolean(false)
    private val writeLock = Any()

    /**
     * Called after each gallery sync (from [FileSyncReporter]'s post-sync hook): the first call of a
     * process also runs the read-only doctor, then the report is rewritten.
     */
    suspend fun onSyncFinished(activeRootId: String? = null): ReportResult {
        runDoctorOnce(activeRootId)
        return write()
    }

    /** Runs the doctor if it has not run in this process yet. Never throws. */
    suspend fun runDoctorOnce(activeRootId: String? = null) {
        val d = doctor ?: return
        if (!doctorRanThisLaunch.compareAndSet(false, true)) return
        try {
            val report = d.run(activeRootId)
            lastDoctor = report
            log.i("doctor", "ran: ${report.checks.count { it.severity >= Severity.WARN }} findings in ${report.totalMs}ms")
        } catch (e: kotlinx.coroutines.CancellationException) {
            doctorRanThisLaunch.set(false)
            throw e
        } catch (e: Throwable) {
            log.w("doctor", "doctor failed: ${e.javaClass.simpleName}")
        }
    }

    fun write(): ReportResult = synchronized(writeLock) {
        try {
            val dir = dirProvider()
                ?: return@synchronized ReportResult.Failed("no external files directory").also {
                    log.w("diag", "report not written: no external files directory")
                }
            // Make the current secrets known before any text is produced or scanned.
            val secretList = runCatching { secrets().filter { it.length >= MIN_SECRET_LEN } }.getOrDefault(emptyList())
            runCatching { redactor.setSecrets(secretList) }

            val text = finalPass(render())
            val problem = leakScan(text, secretList)
            val file = File(dir, REPORT_NAME)
            if (problem != null) {
                // Never log or write the secret itself, only which check tripped.
                log.e("diag", "report blocked: redaction failed ($problem)")
                writeAtomically(file, blockedNotice(problem))
                return@synchronized ReportResult.Blocked(problem)
            }
            val bytes = writeAtomically(file, text)
            ReportResult.Written(file, bytes)
        } catch (e: Throwable) {
            log.w("diag", "report write failed: ${e.javaClass.simpleName}")
            ReportResult.Failed(e.javaClass.simpleName)
        }
    }

    /** Builds the report text, trimming the log tail so the result fits in [maxBytes]. */
    internal fun render(): String {
        val head = StringBuilder()
        head.append("SmugView diagnostics report\n")
        head.append("generated: ").append(java.time.Instant.ofEpochMilli(clock())).append(" (UTC)\n")
        runCatching { header() }.getOrDefault("").takeIf { it.isNotBlank() }?.let { head.append(it.trimEnd()).append('\n') }
        head.append("\n== doctor ==\n")
        val d = lastDoctor
        if (d == null) {
            head.append("not run yet in this process\n")
        } else {
            head.append("totalMs=").append(d.totalMs)
            if (d.ranDuringSync) head.append(" (a sync was open: counts may be transient)")
            head.append('\n')
            fun line(c: CheckResult) {
                head.append('[').append(c.severity).append("] ").append(c.id).append(" = ").append(c.total)
                    .append("  ").append(c.label).append('\n')
            }
            val (offline, invariants) = d.checks.partition { it.id.startsWith("offline_") }
            invariants.forEach(::line)
            // Phase 5 (5-10): counts and one byte total, never a key, title, URL or file name.
            if (offline.isNotEmpty()) {
                head.append("\n== offline files (counts only) ==\n")
                offline.forEach(::line)
            }
        }
        head.append("\n== last sync runs (newest last) ==\n")
        val runs = runCatching { reporter.recent(5) }.getOrDefault(emptyList())
        if (runs.isEmpty()) head.append("none\n") else for (r in runs) head.append(r.summary()).append('\n')
        runCatching { httpStats() }.getOrNull()?.let { s ->
            head.append("\n== http stats (since process start) ==\n")
            head.append("api: ").append(s.api).append('\n')
            head.append("img: ").append(s.img).append('\n')
        }
        head.append("\n== log tail (oldest first, redacted) ==\n")

        val headBytes = utf8Len(head)
        val budget = maxBytes - headBytes - SLACK_BYTES
        if (budget <= 0) return head.toString() + "<log omitted: no room>\n"

        runCatching { log.flush(2000) }
        var tail = runCatching { log.readPersisted(budget) }.getOrDefault("")
        // readPersisted(max) is a request, not a promise: trim here, on a line boundary, from the old end.
        tail = trimToBytes(tail, budget)
        if (tail.isEmpty()) tail = "<empty log>\n"
        else if (!tail.endsWith("\n")) tail += "\n"
        return head.toString() + tail
    }

    private fun trimToBytes(text: String, budget: Int): String {
        var s = text
        if (utf8Len(s) <= budget) return s
        // Chars are at most 3 UTF-8 bytes (non-BMP pairs are 2 chars for 4 bytes): start from a safe estimate.
        s = s.takeLast(budget)
        while (utf8Len(s) > budget) s = s.drop(maxOf(1, (utf8Len(s) - budget) / 3))
        val nl = s.indexOf('\n')
        return if (nl in 0 until s.length - 1) s.substring(nl + 1) else s
    }

    private fun utf8Len(s: CharSequence): Int = s.toString().toByteArray(Charsets.UTF_8).size

    /** Returns a reason (never the secret) if anything credential-like survived, else null. */
    internal fun leakScan(text: String, secretList: Collection<String>): String? {
        // Our own markers can contain a short secret ("path"), which is not a leak.
        val body = MARKER_STRIP.replace(text, "")
        for (s in secretList) {
            if (body.contains(s) || body.contains(URLEncoder.encode(s, "UTF-8"))) return "a secret literal survived"
        }
        for ((name, regex) in PATTERNS) if (regex.containsMatchIn(text)) return "pattern $name"
        return null
    }

    private fun blockedNotice(reason: String): String =
        "SmugView diagnostics report\n" +
            "generated: ${java.time.Instant.ofEpochMilli(clock())} (UTC)\n\n" +
            "REPORT WITHHELD: redaction check failed ($reason).\n" +
            "Nothing from the log was written. See the E line 'report blocked' in the app log.\n"

    private fun writeAtomically(file: File, text: String): Int {
        file.parentFile?.mkdirs()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            // Some file systems refuse rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
        }
        return bytes.size
    }

    companion object {
        const val REPORT_NAME = "report.txt"
        const val MAX_BYTES = 1_500_000
        private const val MIN_SECRET_LEN = 4
        private const val SLACK_BYTES = 256

        private val MARKER_STRIP = Regex("""<secret>|<r>|<path>|<len=\d+>""")

        // Anything the Redactor rules should already have replaced with <r>.
        private val PATTERNS: List<Pair<String, Regex>> = listOf(
            "APIKey=" to Regex("""(?i)\b(?:APIKey|api_key)=(?!<r>|<secret>)[^&\s"'#]{6,}"""),
            "Password=" to Regex("""(?i)\bPassword=(?!<r>|<secret>)[^&\s"'#]+"""),
            "oauth_" to Regex("""(?i)\boauth_[a-z_]+=(?!<r>|<secret>)[^&\s"'#]{4,}"""),
            "access_token=" to Regex("""(?i)\baccess_token=(?!<r>|<secret>)[^&\s"'#]{4,}"""),
            "json credential" to Regex("""(?i)"(?:APIKey|Password|oauth_[a-z_]+|access_token)"\s*:\s*"(?!<r>|<secret>)[^"]+""""),
            "credential header" to Regex("""(?im)^[ \t]*(?:Cookie|Set-Cookie|Authorization|Proxy-Authorization)[ \t]*:[ \t]*(?!<r>)\S"""),
            "bearer token" to Regex("""(?i)\bBearer\s+(?!<r>)[A-Za-z0-9._~+/=-]{8,}""")
        )
    }
}
