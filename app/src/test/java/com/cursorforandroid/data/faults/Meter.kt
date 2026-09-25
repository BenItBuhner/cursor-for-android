package com.cursorforandroid.data.faults

import java.util.concurrent.ConcurrentHashMap

/**
 * What a soak costs, measured on the JVM around a [FaultRig]: the heap still reachable after a collection at each
 * [checkpoint] (the peak of those is the budget, not the garbage between them), bytes on the wire both ways, calls,
 * and bytes allocated by the app's threads (see [isApp]). Allocation is read per thread (HotSpot's counters), each
 * thread's highest reading kept as it is sampled, so a pool thread that ends between samples loses only its last
 * stretch.
 */
class Meter(private val rig: FaultRig) : AutoCloseable {

    // Through reflection: the unit tests compile against android.jar, which has no java.lang.management.
    private val threads: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val hotspot = Class.forName("com.sun.management.ThreadMXBean")
    private val allocatedBytesOf = hotspot.getMethod("getThreadAllocatedBytes", LongArray::class.java)
    private val allocated = ConcurrentHashMap<Long, Long>()
    private val startAllocated = HashMap<Long, Long>()
    private val startIn = rig.bytesIn.get()
    private val startOut = rig.bytesOut.get()
    private val startCalls = rig.calls.get()
    private val baseHeap = retainedHeap()
    @Volatile private var running = true
    /** The highest post-collection heap seen at a [checkpoint], above what it was when the meter started. */
    var peakRetained = 0L
        private set
    private val sampler: Thread

    init {
        hotspot.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(threads, true)
        sample(into = startAllocated)
        sampler = Thread({
            while (running) {
                sample()
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }, "meter-sampler").apply { isDaemon = true; start() }
    }

    private fun sample(into: MutableMap<Long, Long> = allocated) {
        val live = Thread.getAllStackTraces().keys.filter { isApp(it.name) }
        val ids = live.map { it.id }.toLongArray()
        val bytes = allocatedBytesOf.invoke(threads, ids) as LongArray
        for (i in ids.indices) if (bytes[i] >= 0) into.merge(ids[i], bytes[i]) { a: Long, b: Long -> maxOf(a, b) }
    }

    /** Collects and records the heap still reachable now. Returns it, in bytes above the start. */
    fun checkpoint(): Long {
        val now = retainedHeap() - baseHeap
        peakRetained = maxOf(peakRetained, now)
        return now
    }

    val bytesIn: Long get() = rig.bytesIn.get() - startIn
    val bytesOut: Long get() = rig.bytesOut.get() - startOut
    val calls: Int get() = rig.calls.get() - startCalls

    /** Bytes allocated since the meter started, by every thread it saw. */
    fun allocatedBytes(): Long {
        sample()
        return allocated.entries.sumOf { (id, bytes) -> bytes - (startAllocated[id] ?: 0L) }
    }

    fun summary(): String =
        "peakRetained=${peakRetained.mb} in=${bytesIn.mb} out=${bytesOut.mb} calls=$calls allocated=${allocatedBytes().mb}"

    override fun close() {
        running = false
        sampler.interrupt()
    }

    companion object {
        /**
         * The app's own threads: coroutine workers (Default and IO, the streams' among them) and the HTTP client's.
         * Not the fake server's, the test's own thread, or Robolectric's, which load resource tables and classes.
         */
        fun isApp(name: String): Boolean = name.startsWith("DefaultDispatcher-worker") || (name.startsWith("OkHttp") && !name.contains("MockWebServer"))

        fun retainedHeap(): Long {
            val rt = Runtime.getRuntime()
            var last = Long.MAX_VALUE
            // Until a collection frees no more: weak references, finalizers and soft caches settle over a few.
            repeat(6) {
                System.gc()
                // Robolectric's resource assets hold megabytes until they are finalized; garbage all the same.
                @Suppress("DEPRECATION") System.runFinalization()
                Thread.sleep(40)
                val used = rt.totalMemory() - rt.freeMemory()
                if (used >= last - (256 shl 10)) return used
                last = used
            }
            return last
        }

        val Long.mb: String get() = "%.1fMB".format(this / 1_048_576.0)
    }
}
