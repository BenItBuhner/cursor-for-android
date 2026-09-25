package com.cursorforandroid.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Crashes kept on the device, for the next launch to offer: nothing leaves the phone unless the user shares it.
 *
 * Two sources. [install] puts a default uncaught-exception handler in front of whatever was there (the platform's,
 * which kills the process; Sentry's when the user turned it on, which chains to this one): the stack trace is written
 * first, synchronously, then [context] — the app's own account of the moment (version, background live sync, streams,
 * chats in memory, threads by pool, memory) — and the previous handler runs as before. [recordExitReasons] reads
 * Android's own record of how earlier processes ended (API 30+), so the deaths no handler sees are kept too: the
 * low-memory killer's, an ANR with its main-thread trace, a native crash, the system's signal.
 *
 * The newest report not yet dismissed is [pending]: the root's card offers it once, and the diagnostics export and
 * Settings carry every kept report ([export]). At most [MAX_REPORTS] are kept, each at most [MAX_REPORT_CHARS].
 */
class CrashLog(
    private val dir: File,
    private val appVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** One kept report: when it happened, its first line (the exception, or the exit reason), and the whole text. */
    data class Report(val atMillis: Long, val headline: String, val text: String, val file: String)

    private val _pending = MutableStateFlow<Report?>(null)
    /** The newest report the user has not dismissed yet, once [load] has read the disk; null when there is none. */
    val pending: StateFlow<Report?> = _pending.asStateFlow()

    @Volatile private var context: () -> String = { "" }

    /**
     * Records every uncaught exception from now on, on any thread, before handing it on to the handler that was
     * installed before. [context] is read on a helper thread with a deadline, so a crash raised while a lock it reads
     * is held elsewhere still leaves its trace behind. The process's handler is put in place once; a later call (a
     * new graph, a new application under test) only changes which log it writes to.
     */
    fun install(context: () -> String) {
        this.context = context
        active = this
        synchronized(CrashLog) {
            if (handlerInstalled) return
            handlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    active?.record(thread.name, error)
                } catch (_: Throwable) {
                    // The report is best effort; the process is going either way, and the handler after this one must run.
                }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Writes the report of [error], raised on [threadName], now. Never throws. */
    fun record(threadName: String, error: Throwable) {
        val at = nowMillis()
        val file = File(dir, "$PREFIX$at-crash.txt")
        // The trace first and on its own: under an OutOfMemoryError the context may not fit, the trace must.
        val head = runCatching {
            buildString {
                appendLine("Cursor for Android $appVersion crashed at ${Instant.ofEpochMilli(at)}")
                appendLine("Uncaught on thread \"$threadName\"")
                appendLine()
                append(stackTrace(error))
            }
        }.getOrElse { "Cursor for Android $appVersion crashed at ${Instant.ofEpochMilli(at)}\n${error.javaClass.name}\n" }
        runCatching {
            dir.mkdirs()
            file.writeText(head.take(MAX_REPORT_CHARS))
        }
        runCatching {
            val state = readContext()
            if (state.isNotBlank()) file.appendText(("\n---- State at the crash ----\n" + state).take(MAX_REPORT_CHARS - head.length.coerceAtMost(MAX_REPORT_CHARS)))
        }
        runCatching { prune() }
    }

    private fun readContext(): String {
        var out = ""
        val reader = Thread({ out = runCatching { context() }.getOrElse { "state unreadable: ${it.javaClass.simpleName}" } }, "crash-context")
        reader.isDaemon = true
        reader.start()
        reader.join(CONTEXT_DEADLINE_MS)
        return if (reader.isAlive) "state unreadable: not answered within ${CONTEXT_DEADLINE_MS} ms (a lock held elsewhere)" else out
    }

    /**
     * Keeps a report for each way an earlier process ended that the handler could not see, or saw only in part —
     * once each, however many launches later it is read. A Java crash the handler already wrote is not kept twice:
     * its exit record's words are added to the report instead. Call off the main thread, once per launch.
     */
    fun recordExitReasons(activityManager: ActivityManager, packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { recordExitReasonsR(activityManager, packageName) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitReasonsR(activityManager: ActivityManager, packageName: String) {
        val seenFile = File(dir, EXIT_SEEN)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val exits = activityManager.getHistoricalProcessExitReasons(packageName, 0, MAX_EXITS_READ)
            .filter { it.timestamp > seen && it.reason in REPORTED_REASONS }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        dir.mkdirs()
        val existing = reportFiles()
        for (exit in exits) {
            val captured = existing.firstOrNull { f -> f.name.endsWith("-crash.txt") && millisOf(f)?.let { it in (exit.timestamp - MATCH_WINDOW_MS)..(exit.timestamp + MATCH_WINDOW_MS) } == true }
            if (exit.reason == ApplicationExitInfo.REASON_CRASH && captured != null) {
                runCatching { captured.appendText("\n---- Android's exit record ----\n" + describe(exit, withTrace = false)) }
                continue
            }
            val file = File(dir, "$PREFIX${exit.timestamp}-exit.txt")
            val text = buildString {
                appendLine("Cursor for Android ended at ${Instant.ofEpochMilli(exit.timestamp)}: ${reasonName(exit.reason)}")
                appendLine("Read by $appVersion at ${Instant.ofEpochMilli(nowMillis())} from Android's exit record")
                appendLine()
                append(describe(exit, withTrace = true))
            }
            runCatching { file.writeText(text.take(MAX_REPORT_CHARS)) }
        }
        runCatching { seenFile.writeText(exits.maxOf { it.timestamp }.toString()) }
        prune()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo, withTrace: Boolean): String = buildString {
        appendLine("reason=${reasonName(exit.reason)} status=${exit.status} importance=${importanceName(exit.importance)}")
        exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("description=${it.take(500)}") }
        appendLine("pss=${exit.pss / 1024} MB rss=${exit.rss / 1024} MB process=${exit.processName}")
        if (!withTrace) return@buildString
        val trace = runCatching { exit.traceInputStream?.use { input -> readCapped(input, MAX_TRACE_BYTES) } }.getOrNull()
        when {
            trace.isNullOrBlank() -> Unit
            exit.reason == ApplicationExitInfo.REASON_ANR -> {
                appendLine()
                appendLine("---- ANR trace (main thread first) ----")
                append(anrMainFirst(trace))
            }
            // A native crash's trace is a tombstone protobuf: not text, and nothing a reader can use from here.
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> appendLine("tombstone: ${trace.length} bytes (binary, not included)")
            else -> {
                appendLine()
                append(trace)
            }
        }
    }

    /** Reads the kept reports and publishes the newest undismissed one. Call off the main thread. */
    fun load() {
        val dismissed = runCatching { File(dir, DISMISSED).readText().trim().toLong() }.getOrDefault(0L)
        val newest = reportFiles().lastOrNull() ?: run { _pending.value = null; return }
        val at = millisOf(newest) ?: return
        if (at <= dismissed) { _pending.value = null; return }
        _pending.value = read(newest)
    }

    /** The user closed the card: the reports stay for the export, the card does not come back for them. */
    fun dismiss() {
        val newest = _pending.value ?: return
        _pending.value = null
        runCatching {
            dir.mkdirs()
            File(dir, DISMISSED).writeText(newest.atMillis.toString())
        }
    }

    /** Every kept report, newest first; empty when there is none. */
    fun reports(): List<Report> = reportFiles().reversed().mapNotNull { read(it) }

    /**
     * Every kept report as one text, newest first, for the share sheet and the diagnostics export; cut at [maxChars]
     * (the share sheet's intent crosses a binder transaction, which refuses a megabyte and takes the app with it).
     */
    fun export(maxChars: Int = Int.MAX_VALUE): String {
        val all = reports()
        if (all.isEmpty()) return "No crash reports on this device.\n"
        val text = all.joinToString("\n\n") { "########## ${it.file} ##########\n${it.text.trimEnd()}" } + "\n"
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n[cut at $maxChars characters]\n"
    }

    private fun read(file: File): Report? {
        val at = millisOf(file) ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return Report(at, headline(text), text, file.name)
    }

    private fun headline(text: String): String {
        val lines = text.lines()
        // A crash: the exception line after the thread line. An exit: the first line says how it ended.
        val exception = lines.drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("Uncaught on thread") && !it.startsWith("Read by") }
        return if (lines.firstOrNull()?.contains(" ended at ") == true) lines.first().substringAfter(": ", lines.first()) else (exception ?: lines.firstOrNull().orEmpty()).take(200)
    }

    private fun reportFiles(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray()).sortedBy { millisOf(it) ?: 0L }

    private fun prune() {
        val files = reportFiles()
        if (files.size > MAX_REPORTS) files.take(files.size - MAX_REPORTS).forEach { it.delete() }
    }

    companion object {
        /** Under the app's files, not its cache: a report must outlive the cache clean-up and a low-storage purge. */
        const val DIRECTORY = "crash-reports"
        const val MAX_REPORTS = 8
        const val MAX_REPORT_CHARS = 120_000
        /** What the share sheet is handed at most: well under the binder's megabyte at two bytes a character. */
        const val SHARE_MAX_CHARS = 150_000
        private const val MAX_TRACE_BYTES = 400_000
        private const val MAX_EXITS_READ = 16
        private const val CONTEXT_DEADLINE_MS = 1_500L
        /** How far apart the handler's own stamp and Android's exit record of the same crash can be. */
        private const val MATCH_WINDOW_MS = 30_000L
        private const val PREFIX = "report-"
        private const val DISMISSED = "dismissed"
        private const val EXIT_SEEN = "exit-seen"

        @Volatile private var active: CrashLog? = null
        private var handlerInstalled = false

        /** Lets a test put its own default handler under the next [install]. */
        @androidx.annotation.VisibleForTesting
        internal fun forgetHandlerForTest() = synchronized(CrashLog) { handlerInstalled = false }

        private fun millisOf(file: File): Long? = file.name.removePrefix(PREFIX).substringBefore('-').toLongOrNull()

        /** The ways a process ends that are the app's doing or the system's verdict on it, not the user's choice. */
        private val REPORTED_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_SIGNALED,
        )

        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "UNKNOWN($reason)"
        }

        private fun importanceName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground-service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
            else -> importance.toString()
        }

        internal fun stackTrace(error: Throwable): String {
            val out = StringWriter()
            PrintWriter(out).use { error.printStackTrace(it) }
            return out.toString()
        }

        private fun readCapped(input: java.io.InputStream, max: Int): String {
            val bytes = ByteArray(max)
            var n = 0
            while (n < max) {
                val read = input.read(bytes, n, max - n)
                if (read < 0) break
                n += read
            }
            return String(bytes, 0, n, Charsets.UTF_8)
        }

        /** An ANR dump lists every thread; the main thread's stack is the one that says why, so it goes first. */
        internal fun anrMainFirst(trace: String): String {
            val start = trace.indexOf("\"main\"")
            if (start < 0) return trace
            val end = trace.indexOf("\n\n", start).let { if (it < 0) trace.length else it }
            return trace.substring(start, end) + "\n\n---- Rest of the dump ----\n" + trace.removeRange(start, end)
        }
    }
}
