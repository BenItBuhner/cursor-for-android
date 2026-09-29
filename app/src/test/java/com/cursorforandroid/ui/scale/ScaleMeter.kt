package com.cursorforandroid.ui.scale

import android.os.Looper
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.cursorforandroid.util.RecomposeCounter
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.locks.LockSupport

/**
 * What the UI scale benchmarks measure, frame by frame, with the clock held: each frame's wall time and the main
 * thread's CPU time and allocations, the scopes it recomposed (a [CompositionObserver] on the root composition and
 * every scope it invalidates), and around a phase the JVM's collections and the heap left after one. Robolectric
 * composes, measures and lays out on the JVM and draws nothing on a GPU: the times are this machine's main-thread
 * costs, not a phone's frame times; the counts (frames, scopes, composables, requests) do not depend on the machine.
 *
 * `SCALE_PROFILE=1` in the environment also samples the main thread's stack every half millisecond during the frames
 * and names the app's lines it was in: `hotAt` over every frame of a phase, `slowAt` over its frames past 16.7 ms.
 * The sampling costs the frames a little, so it is off unless asked for.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class ScaleMeter(private val compose: ComposeTestRule) {

    class Recompositions : CompositionObserver, RecomposeScopeObserver {
        @Volatile var scopes = 0
        private val observed = Collections.newSetFromMap(IdentityHashMap<RecomposeScope, Boolean>())
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) { scopes++ }
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }
    }

    val recompositions = Recompositions()

    /**
     * Samples [target]'s stack while [sink] is set: each sample is the innermost app line on it (outside this
     * harness), or failing one the innermost frame. One thread for the whole run, pointed at each meter's main thread.
     */
    object Sampler {
        val enabled: Boolean = System.getenv("SCALE_PROFILE")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true
        private const val INTERVAL_NANOS = 500_000L
        @Volatile var target: Thread? = null
        @Volatile var sink: HashMap<String, Int>? = null

        private val worker by lazy {
            Thread({
                while (true) {
                    val into = sink
                    val on = target
                    if (into != null && on != null) {
                        val key = key(on.stackTrace)
                        synchronized(into) { into.merge(key, 1, Int::plus) }
                    }
                    LockSupport.parkNanos(INTERVAL_NANOS)
                }
            }, "scale-sampler").apply { isDaemon = true; start() }
        }

        fun watch(thread: Thread) {
            target = thread
            worker
        }

        /** The innermost app line, then where under it the thread was: `OpenStretches.kt:167(holdingHeight)>text`. */
        private fun key(stack: Array<StackTraceElement>): String {
            val app = stack.firstOrNull { it.className.startsWith("com.cursorforandroid.") && !it.className.startsWith("com.cursorforandroid.ui.scale.") && !it.className.startsWith("com.cursorforandroid.fixtures.") }
            val line = app?.let { "${it.fileName}:${it.lineNumber}(${it.methodName.substringBefore('$')})" } ?: "-"
            return "$line>${area(stack)}"
        }

        /** The kind of work the innermost frames are: the app's own, text layout, a lazy list's measure, Robolectric's shadows… */
        private fun area(stack: Array<StackTraceElement>): String {
            for (frame in stack.take(40)) {
                val c = frame.className
                when {
                    c.startsWith("java.lang.invoke.") || c.startsWith("jdk.internal.") || c.startsWith("java.lang.reflect.") -> continue
                    c.startsWith("org.robolectric.") -> return "robolectric"
                    c.startsWith("android.text.") || c.startsWith("android.graphics.text.") || c.startsWith("androidx.compose.ui.text.") || c.startsWith("androidx.compose.foundation.text.") -> return "text"
                    c.startsWith("android.graphics.") || c.startsWith("androidx.compose.ui.graphics.") -> return "graphics"
                    c.startsWith("androidx.compose.foundation.lazy.") -> return "lazy"
                    c.startsWith("androidx.compose.runtime.") -> return "runtime"
                    c.startsWith("androidx.compose.ui.node.") || c.startsWith("androidx.compose.ui.layout.") -> return "layout"
                    c.startsWith("androidx.compose.ui.semantics.") || c.startsWith("androidx.compose.ui.platform.") -> return "semantics"
                    c.startsWith("com.cursorforandroid.") -> return "app"
                    c.startsWith("kotlin.") || c.startsWith("kotlinx.") || c.startsWith("java.") -> return "stdlib"
                    else -> return c.split('.').take(3).joinToString(".")
                }
            }
            return "?"
        }
    }

    private val sampling = Sampler.enabled.also { if (it) Sampler.watch(Thread.currentThread()) }

    /** The JVM's management beans through reflection: the tests compile against the Android SDK and run on a JVM. */
    object Jvm {
        private fun bean(name: String): Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod(name).invoke(null) }.getOrNull()
        private val thread = bean("getThreadMXBean")
        private val os = bean("getOperatingSystemMXBean")
        private val memory = bean("getMemoryMXBean")
        private val cpu = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()
        private val alloc = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes") }.getOrNull()
        private val processCpu = runCatching { Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime") }.getOrNull()
        private val heapUsage = runCatching { Class.forName("java.lang.management.MemoryMXBean").getMethod("getHeapMemoryUsage") }.getOrNull()
        private val used = runCatching { Class.forName("java.lang.management.MemoryUsage").getMethod("getUsed") }.getOrNull()
        private val gcBeans = runCatching {
            (Class.forName("java.lang.management.ManagementFactory").getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>).filterNotNull()
        }.getOrDefault(emptyList())
        private val gcCount = runCatching { Class.forName("java.lang.management.GarbageCollectorMXBean").getMethod("getCollectionCount") }.getOrNull()
        private val gcTime = runCatching { Class.forName("java.lang.management.GarbageCollectorMXBean").getMethod("getCollectionTime") }.getOrNull()

        fun cpuNanos(): Long = runCatching { cpu?.invoke(thread) as? Long }.getOrNull() ?: 0L
        fun allocatedBytes(): Long = runCatching { alloc?.invoke(thread) as? Long }.getOrNull() ?: 0L
        /** CPU time of the whole process, every thread: the list's organizing on the default dispatcher included. */
        fun processCpuNanos(): Long = runCatching { processCpu?.invoke(os) as? Long }.getOrNull() ?: 0L
        fun gcCount(): Long = gcBeans.sumOf { b -> runCatching { gcCount?.invoke(b) as? Long }.getOrNull()?.coerceAtLeast(0) ?: 0L }
        fun gcMillis(): Long = gcBeans.sumOf { b -> runCatching { gcTime?.invoke(b) as? Long }.getOrNull()?.coerceAtLeast(0) ?: 0L }
        private val allIds = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getAllThreadIds") }.getOrNull()
        private val threadCpu = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadCpuTime", Long::class.javaPrimitiveType) }.getOrNull()
        private val threadAlloc = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType) }.getOrNull()
        private val threadInfo = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadInfo", Long::class.javaPrimitiveType) }.getOrNull()
        private val threadName = runCatching { Class.forName("java.lang.management.ThreadInfo").getMethod("getThreadName") }.getOrNull()
        private val names = HashMap<Long, String>()

        /** CPU nanoseconds and allocated bytes so far of every live thread, summed by pool (its name without the numbers). */
        fun threads(): Map<String, LongArray> {
            val ids = runCatching { allIds?.invoke(thread) as? LongArray }.getOrNull() ?: return emptyMap()
            val out = HashMap<String, LongArray>()
            val current = Thread.currentThread().id
            for (id in ids) {
                val name = if (id == current) "main" else names.getOrPut(id) { pool(runCatching { threadName?.invoke(threadInfo?.invoke(thread, id)) as? String }.getOrNull() ?: "?") }
                val cpuNs = runCatching { threadCpu?.invoke(thread, id) as? Long }.getOrNull()?.coerceAtLeast(0) ?: 0L
                val bytes = runCatching { threadAlloc?.invoke(thread, id) as? Long }.getOrNull()?.coerceAtLeast(0) ?: 0L
                out.getOrPut(name) { LongArray(2) }.let { it[0] += cpuNs; it[1] += bytes }
            }
            return out
        }

        private fun pool(name: String): String = when {
            name.startsWith("DefaultDispatcher") -> "default"
            name.contains("CompilerThread") -> "jit"
            name.startsWith("OkHttp") -> "okhttp"
            else -> name.replace(Regex("[-# ]?\\d+"), "").ifEmpty { "?" }.replace(' ', '_')
        }

        /** The heap in use after a full collection: what the screen and the fleet behind it keep. */
        fun retainedHeapBytes(): Long {
            repeat(2) { System.gc(); Thread.sleep(40) }
            return runCatching { used?.invoke(heapUsage?.invoke(memory)) as? Long }.getOrNull() ?: 0L
        }
    }

    /** One stretch of a scenario, measured frame by frame. */
    class Phase(val scenario: String, val fleet: String) {
        val wall = ArrayList<Double>()
        val cpu = ArrayList<Double>()
        val frameScopes = ArrayList<Int>()
        /** Scopes recomposed in the frames that follow each tick until the next, one entry per tick. */
        val tickScopes = ArrayList<Int>()
        val tickAlloc = ArrayList<Long>()
        /** The test thread's cost of handing each tick to the app (the repository's publications), outside the frames. */
        val deliverMs = ArrayList<Double>()
        var allocated = 0L
        var ticks = 0
        var processCpuNanos = 0L
        var gcCount = 0L
        var gcMillis = 0L
        var spanMillis = 0L
        var heapBytes = 0L
        val extra = LinkedHashMap<String, Any>()
        var top: List<Pair<String, Int>> = emptyList()
        /** CPU nanoseconds and allocated bytes over the phase, by thread pool (see [Jvm.threads]). */
        val pools = HashMap<String, LongArray>()
        /** With [Sampler.enabled]: the main thread's samples by app line, over every frame and over the frames past 16.7 ms. */
        val hotAt = HashMap<String, Int>()
        val slowAt = HashMap<String, Int>()

        /** Seconds the phase covers on the app's clock: sixty frames a second. */
        val simulatedSeconds: Double get() = (frames / 60.0).coerceAtLeast(1.0 / 60)

        val scopes: Int get() = frameScopes.sum()
        val frames: Int get() = wall.size

        fun line(): String = buildString {
            append("SCALE $scenario fleet=$fleet frames=$frames")
            append(" wallP50=${f(wall.pct(0.5))} wallP95=${f(wall.pct(0.95))} wallP99=${f(wall.pct(0.99))} wallMax=${f(wall.maxOrNull() ?: 0.0)}")
            append(" cpuP50=${f(cpu.pct(0.5))} cpuP95=${f(cpu.pct(0.95))} cpuP99=${f(cpu.pct(0.99))} cpuMax=${f(cpu.maxOrNull() ?: 0.0)}")
            append(" over16=${wall.count { it > 16.7 }} over33=${wall.count { it > 33.3 }}")
            append(" scopes=$scopes scopesP95=${frameScopes.map(Int::toDouble).pct(0.95).toInt()} scopesMax=${frameScopes.maxOrNull() ?: 0}")
            if (ticks > 0) append(" ticks=$ticks")
            if (tickScopes.isNotEmpty()) {
                append(" scopesPerTick=${tickScopes.map(Int::toDouble).pct(0.5).toInt()} scopesPerTickMax=${tickScopes.maxOrNull() ?: 0}")
                append(" allocPerTickKB=${(tickAlloc.map(Long::toDouble).pct(0.5) / 1024).toInt()}")
                append(" deliverP50=${f(deliverMs.pct(0.5))} deliverMax=${f(deliverMs.maxOrNull() ?: 0.0)}")
            }
            val seconds = simulatedSeconds
            append(" mainCpuMsPerSec=${f(cpu.sum() / seconds)} procCpuMsPerSec=${f(processCpuNanos / 1e6 / seconds)}")
            pools["default"]?.let { append(" defaultCpuMsPerSec=${f(it[0] / 1e6 / seconds)} defaultAllocMBPerSec=${f(it[1] / 1048576.0 / seconds)}") }
            append(" allocMB=${f(allocated / 1048576.0)} gc=$gcCount gcMs=$gcMillis realMs=$spanMillis")
            if (pools.isNotEmpty()) {
                append(" cpuByPool=" + pools.entries.filter { it.value[0] > 0 }.sortedByDescending { it.value[0] }.take(5).joinToString(",") { "${it.key}:${f(it.value[0] / 1e6)}" })
            }
            if (heapBytes > 0) append(" heapMB=${f(heapBytes / 1048576.0)}")
            extra.forEach { (k, v) -> append(" $k=$v") }
            if (top.isNotEmpty()) append(" top=${top.joinToString(",") { "${it.first}:${it.second}" }}")
            if (hotAt.isNotEmpty()) append(" hotAt=${ranked(hotAt)}")
            if (slowAt.isNotEmpty()) append(" slowAt=${ranked(slowAt)}")
        }

        private fun ranked(samples: Map<String, Int>) = samples.entries.sortedByDescending { it.value }.take(8).joinToString(",") { "${it.key}=${it.value}" }

        companion object {
            fun f(v: Double) = "%.2f".format(v)
            fun List<Double>.pct(p: Double): Double = if (isEmpty()) 0.0 else sorted()[(size * p).toInt().coerceAtMost(size - 1)]
        }
    }

    private var phaseStart = 0L
    private var procCpu0 = 0L
    private var gc0 = 0L
    private var gcMs0 = 0L
    private var pools0: Map<String, LongArray> = emptyMap()

    fun begin(phase: Phase): Phase {
        phaseStart = System.nanoTime()
        procCpu0 = Jvm.processCpuNanos()
        gc0 = Jvm.gcCount()
        gcMs0 = Jvm.gcMillis()
        pools0 = Jvm.threads()
        return phase
    }

    /** Closes [phase]: its span, the process's CPU, the collections; with [heap], the heap kept after a collection. */
    fun end(phase: Phase, heap: Boolean = false): Phase {
        phase.spanMillis += (System.nanoTime() - phaseStart) / 1_000_000
        phase.processCpuNanos += Jvm.processCpuNanos() - procCpu0
        phase.gcCount += Jvm.gcCount() - gc0
        phase.gcMillis += Jvm.gcMillis() - gcMs0
        Jvm.threads().forEach { (pool, now) ->
            val was = pools0[pool] ?: LongArray(2)
            phase.pools.getOrPut(pool) { LongArray(2) }.let { it[0] += (now[0] - was[0]).coerceAtLeast(0); it[1] += (now[1] - was[1]).coerceAtLeast(0) }
        }
        if (heap) phase.heapBytes = Jvm.retainedHeapBytes()
        return phase
    }

    /** One frame with the clock held: the main looper's due work, a frame of the clock, what that recomposes and lays out. */
    fun frame(phase: Phase?) = measured(phase) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** [action] as a frame of its own (a tap dispatched, composed and laid out), measured into [phase] when there is one. */
    fun measured(phase: Phase?, action: () -> Unit) {
        val scopes = recompositions.scopes
        val alloc0 = Jvm.allocatedBytes()
        val cpu0 = Jvm.cpuNanos()
        val samples = if (phase != null && sampling) HashMap<String, Int>().also { Sampler.sink = it } else null
        val t0 = System.nanoTime()
        action()
        if (samples != null) Sampler.sink = null
        if (phase == null) return
        val wallMs = (System.nanoTime() - t0) / 1e6
        phase.wall += wallMs
        if (samples != null) synchronized(samples) {
            samples.forEach { (at, n) ->
                phase.hotAt.merge(at, n, Int::plus)
                if (wallMs > 16.7) phase.slowAt.merge(at, n, Int::plus)
            }
        }
        phase.cpu += (Jvm.cpuNanos() - cpu0) / 1e6
        phase.allocated += Jvm.allocatedBytes() - alloc0
        phase.frameScopes += recompositions.scopes - scopes
    }

    /**
     * Frames until [changed] says what the app publishes off the main thread has landed (a sleep of [paceMs] before
     * each, the pace of a phone's frames, up to [maxWaitFrames]), then frames until [frames] have run in all.
     */
    fun frames(phase: Phase?, frames: Int, changed: () -> Boolean = { true }, paceMs: Long = 2, maxWaitFrames: Int = 30) {
        var n = 0
        while (n < frames && n < maxWaitFrames && !changed()) { Thread.sleep(paceMs); frame(phase); n++ }
        while (n < frames) { frame(phase); n++ }
    }

    /**
     * Frames until nothing recomposes for [quiet] frames in a row, at most [max]; how many ran. Measured into [phase].
     */
    fun settle(phase: Phase?, quiet: Int = 5, max: Int = 120, paceMs: Long = 2): Int {
        var still = 0
        var n = 0
        while (n < max && still < quiet) {
            val before = recompositions.scopes
            Thread.sleep(paceMs)
            frame(phase)
            n++
            if (recompositions.scopes == before) still++ else still = 0
        }
        return n
    }

    /** The composables that ran during [block], by name (the compiler's trace markers, see [RecomposeCounter]), top [n]. */
    fun <T> attributed(n: Int = 8, block: () -> T): Pair<T, List<Pair<String, Int>>> {
        RecomposeCounter.install()
        RecomposeCounter.reset()
        try {
            val result = block()
            val top = RecomposeCounter.snapshot().entries.sortedByDescending { it.value }.take(n).map { it.key.removePrefix("ui.").replace(' ', '_') to it.value }
            return result to top
        } finally {
            RecomposeCounter.uninstall()
        }
    }
}
