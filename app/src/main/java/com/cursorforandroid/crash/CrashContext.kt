package com.cursorforandroid.crash

import android.app.ActivityManager
import android.os.Debug
import java.time.Instant

/**
 * The runtime half of a crash report's state: the heap against its limit, native memory, whether the system called
 * memory low, and every live thread counted by pool — the numbers that tell an out-of-memory death, a thread leak and
 * a pool exhausted apart. The app's half (live sync, streams, chats in memory) is added by the graph.
 */
object CrashContext {

    fun runtime(activityManager: ActivityManager?): String = buildString {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        appendLine("heap: used=${mb(used)} MB of max=${mb(rt.maxMemory())} MB (total=${mb(rt.totalMemory())} MB)")
        runCatching { appendLine("native heap: allocated=${mb(Debug.getNativeHeapAllocatedSize())} MB") }
        activityManager?.let { am ->
            runCatching {
                val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                appendLine("device: available=${mb(info.availMem)} MB of ${mb(info.totalMem)} MB lowMemory=${info.lowMemory} memoryClass=${am.memoryClass} MB largeMemoryClass=${am.largeMemoryClass} MB")
            }
        }
        append(threads())
    }

    /** Every live thread, counted by pool (the name with its numbers taken out), largest pools first. */
    fun threads(): String = buildString {
        val all = runCatching { Thread.getAllStackTraces().keys }.getOrDefault(emptySet())
        appendLine("threads: ${all.size}")
        all.groupingBy { pool(it.name) }.eachCount().entries.sortedByDescending { it.value }.take(MAX_POOLS).forEach { (name, n) ->
            appendLine("  $n × $name")
        }
    }

    /** `DefaultDispatcher-worker-12` → `DefaultDispatcher-worker-#`; OkHttp's per-call names lose their URL. */
    internal fun pool(name: String): String = when {
        name.startsWith("OkHttp ") && name.contains("://") -> "OkHttp <call>"
        else -> name.replace(DIGITS, "#")
    }

    private val DIGITS = Regex("\\d+")
    private const val MAX_POOLS = 25
    private fun mb(bytes: Long) = bytes / (1024 * 1024)
}

/**
 * The last things the app did that a crash could follow from — a chat opened or left, a hold taken or dropped, a
 * Project opened — for the crash report's state. Ids only by their tails; nothing written in a chat. Bounded.
 */
object Breadcrumbs {
    private const val MAX = 60
    private val ring = ArrayDeque<String>(MAX)

    fun add(what: String) {
        val line = "${Instant.ofEpochMilli(System.currentTimeMillis())} $what"
        synchronized(ring) {
            if (ring.size == MAX) ring.removeFirst()
            ring.addLast(line)
        }
    }

    fun lines(): List<String> = synchronized(ring) { ring.toList() }

    /** The last characters of an id, as every diagnostics export shortens them. */
    fun tail(id: String): String = id.takeLast(6)
}
