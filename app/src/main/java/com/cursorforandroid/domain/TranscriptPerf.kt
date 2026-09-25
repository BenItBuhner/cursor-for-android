package com.cursorforandroid.domain

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the transcript pipeline of one chat cost since the chat was opened: the moments the first content and the
 * whole newest page were published, how many times the transcript was published, how many rows the presenter
 * built against how many it reused, what the markdown cache answered, how many files and network calls the open
 * took, and how many rows the screen composed. Plain counters, a few atomics a publication: cheap enough to run
 * always, so the `perf:` block of the transcript diagnostics can say where a slow chat spent its time. Nothing of
 * any message is in it.
 */
object TranscriptPerf {

    /** The counters of one chat, from its last open ([opened]). */
    class Session internal constructor(val agentId: String, private val clock: () -> Long) {
        @Volatile var openedAtNanos: Long = clock()
            private set
        private val firstContentAt = AtomicLong(0L)
        private val newestPageWholeAt = AtomicLong(0L)
        private val publications = AtomicInteger(0)
        private val publicationStamps = Stamps()
        private val publishNanos = AtomicLong(0L)
        private val publishMaxNanos = AtomicLong(0L)
        private val presenterRuns = AtomicInteger(0)
        private val presenterNanos = AtomicLong(0L)
        private val presenterMaxNanos = AtomicLong(0L)
        private val rowsBuilt = AtomicInteger(0)
        private val rowsReused = AtomicInteger(0)
        private val rowCompositions = AtomicInteger(0)
        private val stepCompositions = AtomicInteger(0)
        private val markdownParses = AtomicInteger(0)
        private val markdownHits = AtomicInteger(0)
        private val markdownParseNanos = AtomicLong(0L)
        private val turnsBuilt = AtomicInteger(0)
        private val turnBuildNanos = AtomicLong(0L)
        private val turnsReused = AtomicInteger(0)
        private val turnsRendered = AtomicInteger(0)
        private val turnRendersReused = AtomicInteger(0)
        private val conversationReads = AtomicInteger(0)
        private val traceReads = AtomicInteger(0)
        private val traceReadNanos = AtomicLong(0L)
        private val network = ConcurrentHashMap<String, AtomicInteger>()
        private val networkStamps = Stamps()

        /** Nanoseconds since the chat was opened. */
        fun sinceOpenNanos(): Long = clock() - openedAtNanos

        /** One publication of [items] items, whose rebuild from the inputs took [buildNanos]. */
        fun publication(items: Int, whole: Boolean, buildNanos: Long = 0L) {
            publications.incrementAndGet()
            publicationStamps.add(clock())
            publishNanos.addAndGet(buildNanos)
            publishMaxNanos.updateAndGet { maxOf(it, buildNanos) }
            if (items > 0) firstContentAt.compareAndSet(0L, clock())
            if (items > 0 && whole) newestPageWholeAt.compareAndSet(0L, clock())
            logger?.let { log -> log("$agentId items=$items whole=$whole\n${snapshot().render()}") }
        }

        fun presenterRun(nanos: Long, built: Int, reused: Int) {
            presenterRuns.incrementAndGet()
            presenterNanos.addAndGet(nanos)
            presenterMaxNanos.updateAndGet { maxOf(it, nanos) }
            rowsBuilt.addAndGet(built)
            rowsReused.addAndGet(reused)
        }

        fun rowComposed() { rowCompositions.incrementAndGet() }

        /** One composition, first or again, of a step inside an open stretch: a thought, a tool call, a note. */
        fun stepComposed() { stepCompositions.incrementAndGet() }

        fun markdownParsed(nanos: Long) { markdownParses.incrementAndGet(); markdownParseNanos.addAndGet(nanos) }
        fun markdownHit() { markdownHits.incrementAndGet() }

        /** One turn's items built from the record's steps (see `RecordTranscript.window`), in [nanos]. */
        fun turnBuilt(nanos: Long) { turnsBuilt.incrementAndGet(); turnBuildNanos.addAndGet(nanos) }
        fun turnReused() { turnsReused.incrementAndGet() }
        /** One record turn rendered into the transcript's items on a publication, or taken as last rendered. */
        fun turnRendered() { turnsRendered.incrementAndGet() }
        fun turnRenderReused() { turnRendersReused.incrementAndGet() }

        fun conversationRead() { conversationReads.incrementAndGet() }
        fun traceRead(files: Int, nanos: Long) { traceReads.addAndGet(files); traceReadNanos.addAndGet(nanos) }

        /** One network call of [kind] (`record`, `state`, `runs`, `run`, `agent`, `transcript`, `replay`). */
        fun network(kind: String) {
            network.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
            networkStamps.add(clock())
        }

        /** The counters as a snapshot: what the `perf:` block prints, and what a benchmark reads. */
        fun snapshot(): Snapshot {
            val now = clock()
            return Snapshot(
                openForMs = (now - openedAtNanos) / 1_000_000,
                firstContentMs = firstContentAt.get().takeIf { it > 0 }?.let { (it - openedAtNanos) / 1_000_000 },
                newestPageWholeMs = newestPageWholeAt.get().takeIf { it > 0 }?.let { (it - openedAtNanos) / 1_000_000 },
                publications = publications.get(),
                publicationsLastMinute = publicationStamps.countSince(now - MINUTE_NANOS),
                publishBuildMs = publishNanos.get() / 1_000_000.0,
                publishBuildMaxMs = publishMaxNanos.get() / 1_000_000.0,
                presenterRuns = presenterRuns.get(),
                presenterTotalMs = presenterNanos.get() / 1_000_000.0,
                presenterMaxMs = presenterMaxNanos.get() / 1_000_000.0,
                rowsBuilt = rowsBuilt.get(),
                rowsReused = rowsReused.get(),
                rowCompositions = rowCompositions.get(),
                stepCompositions = stepCompositions.get(),
                markdownParses = markdownParses.get(),
                markdownHits = markdownHits.get(),
                markdownParseMs = markdownParseNanos.get() / 1_000_000.0,
                turnsBuilt = turnsBuilt.get(),
                turnBuildMs = turnBuildNanos.get() / 1_000_000.0,
                turnsReused = turnsReused.get(),
                turnsRendered = turnsRendered.get(),
                turnRendersReused = turnRendersReused.get(),
                conversationReads = conversationReads.get(),
                traceReads = traceReads.get(),
                traceReadMs = traceReadNanos.get() / 1_000_000.0,
                network = network.entries.sortedBy { it.key }.associate { it.key to it.value.get() },
                networkLastMinute = networkStamps.countSince(now - MINUTE_NANOS),
            )
        }

        internal fun reopen() {
            openedAtNanos = clock()
            firstContentAt.set(0L)
            newestPageWholeAt.set(0L)
            publications.set(0)
            publicationStamps.clear()
            publishNanos.set(0L)
            publishMaxNanos.set(0L)
            presenterRuns.set(0)
            presenterNanos.set(0L)
            presenterMaxNanos.set(0L)
            rowsBuilt.set(0)
            rowsReused.set(0)
            rowCompositions.set(0)
            stepCompositions.set(0)
            markdownParses.set(0)
            markdownHits.set(0)
            markdownParseNanos.set(0L)
            turnsBuilt.set(0)
            turnBuildNanos.set(0L)
            turnsReused.set(0)
            turnsRendered.set(0)
            turnRendersReused.set(0)
            conversationReads.set(0)
            traceReads.set(0)
            traceReadNanos.set(0L)
            network.clear()
            networkStamps.clear()
        }
    }

    /** The counters at one moment (see [Session.snapshot]). Times are from the open. */
    data class Snapshot(
        val openForMs: Long,
        val firstContentMs: Long?,
        val newestPageWholeMs: Long?,
        val publications: Int,
        val publicationsLastMinute: Int,
        /** What rebuilding the items from the inputs cost across the publications, and the worst one. */
        val publishBuildMs: Double,
        val publishBuildMaxMs: Double,
        val presenterRuns: Int,
        val presenterTotalMs: Double,
        val presenterMaxMs: Double,
        val rowsBuilt: Int,
        val rowsReused: Int,
        val rowCompositions: Int,
        val stepCompositions: Int = 0,
        val markdownParses: Int,
        val markdownHits: Int,
        val markdownParseMs: Double,
        val turnsBuilt: Int,
        val turnBuildMs: Double,
        val turnsReused: Int,
        /** Record turns rendered into items on publications, against those taken as last rendered (see `ConversationRepository.Entry.recordItems`). */
        val turnsRendered: Int,
        val turnRendersReused: Int,
        val conversationReads: Int,
        val traceReads: Int,
        val traceReadMs: Double,
        val network: Map<String, Int>,
        val networkLastMinute: Int,
    ) {
        val networkTotal: Int get() = network.values.sum()
        val presenterAvgMs: Double get() = if (presenterRuns == 0) 0.0 else presenterTotalMs / presenterRuns

        /** Publications per minute over the chat's open time, never over less than a minute: a chat open for two seconds is not read as sixty a minute. */
        val publicationsPerMinute: Double get() = perMinute(publications)
        val networkPerMinute: Double get() = perMinute(networkTotal)

        private fun perMinute(count: Int): Double = count / maxOf(openForMs / 60_000.0, 1.0)

        /** The `perf:` block of the transcript diagnostics. */
        fun render(): String = buildString {
            appendLine(
                "perf: open=${ms(openForMs)} firstContent=${firstContentMs?.let(::ms) ?: "-"} newestPageWhole=${newestPageWholeMs?.let(::ms) ?: "-"}" +
                    " publications=$publications (${fmt(publicationsPerMinute)}/min, lastMinute=$publicationsLastMinute) itemsRebuilt=${fmt(publishBuildMs)}ms (max ${fmt(publishBuildMaxMs)}ms)",
            )
            appendLine(
                "  presenter: runs=$presenterRuns total=${fmt(presenterTotalMs)}ms avg=${fmt(presenterAvgMs)}ms max=${fmt(presenterMaxMs)}ms" +
                    " rowsMaterialized=$rowsBuilt rowsReused=$rowsReused rowsComposed=$rowCompositions stepsComposed=$stepCompositions turnsBuilt=$turnsBuilt (${fmt(turnBuildMs)}ms) turnsReused=$turnsReused turnsRendered=$turnsRendered turnRendersReused=$turnRendersReused",
            )
            appendLine("  markdown: parses=$markdownParses cacheHits=$markdownHits parseTime=${fmt(markdownParseMs)}ms")
            appendLine("  disk: conversationReads=$conversationReads traceFileReads=$traceReads (${fmt(traceReadMs)}ms)")
            append("  network: total=$networkTotal (${fmt(networkPerMinute)}/min, lastMinute=$networkLastMinute)")
            if (network.isNotEmpty()) append(" ").append(network.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }

        private fun ms(value: Long): String = if (value >= 10_000) "${fmt(value / 1000.0)}s" else "${value}ms"
        private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)
    }

    /** The last [capacity] moments something happened, for "how many in the last minute". */
    private class Stamps(private val capacity: Int = 512) {
        private val ring = LongArray(capacity)
        private var count = 0
        private var next = 0

        @Synchronized fun add(nanos: Long) {
            ring[next] = nanos
            next = (next + 1) % capacity
            if (count < capacity) count++
        }

        @Synchronized fun countSince(nanos: Long): Int {
            var n = 0
            for (i in 0 until count) if (ring[i] >= nanos) n++
            return n
        }

        @Synchronized fun clear() { count = 0; next = 0 }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var clock: () -> Long = System::nanoTime

    /** The counters of [agentId], started at the first ask. */
    fun session(agentId: String): Session {
        sessions[agentId]?.let { return it }
        if (sessions.size >= MAX_SESSIONS) sessions.keys.firstOrNull()?.let { sessions.remove(it) }
        return sessions.getOrPut(agentId) { Session(agentId, clock) }
    }

    /** The counters of [agentId] when the chat has been opened this process, else null. */
    fun sessionOrNull(agentId: String): Session? = sessions[agentId]

    /** A screen opened the chat: the counters start over from now, and the chat is the one the screen-side counters go to. */
    fun opened(agentId: String): Session = session(agentId).also { it.reopen(); focused = it }

    /**
     * The chat a screen last opened: where the counters that know no chat — the markdown cache's, the presenter's,
     * the rows composed — are filed. Null before any chat was opened this process.
     */
    @Volatile var focused: Session? = null
        private set

    /** The clock the stamps are read from; a test may hold it. */
    fun useClock(nanos: () -> Long) { clock = nanos }

    /**
     * Where a debug build measuring the transcript on a device writes the `perf:` block after each publication
     * (logcat); null — the default, and every release build — writes nothing.
     */
    @Volatile var logger: ((String) -> Unit)? = null

    fun clearAll() = sessions.clear()

    private const val MAX_SESSIONS = 8
    private const val MINUTE_NANOS = 60_000_000_000L
}
