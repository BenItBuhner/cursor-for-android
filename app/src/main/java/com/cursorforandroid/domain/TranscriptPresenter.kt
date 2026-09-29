package com.cursorforandroid.domain

/**
 * Turns a conversation's items into what the screen draws — the items as the transcript shows them (every cached
 * call re-read, a coordinator's remarks folded, its goal calls lifted; see [CoordinatorTranscript.present] and
 * [GoalTranscript.lift]) and the rows cut from them (see [TranscriptRows]) — incrementally, off the main thread.
 *
 * Until 0.3.31 the screen did all of this in composition, over the whole transcript, on every publication: a burst
 * of live deltas or a page of older turns re-presented and re-cut hundreds of turns on the main thread, ten times a
 * second, a megabyte of garbage each. Here the transcript is cut at its user messages into segments — a segment is a
 * prompt and everything up to the next prompt — and each segment is presented and cut on its own: the passes above
 * never look across a user message (a stretch closes at one, a remark folds within its turn, a lifted goal call reads
 * its turn's start from it), so the rows of the whole are the rows of the segments in order, plus the two words that
 * belong to the whole alone — the newest stretch reads "Working" while the run writes ([TranscriptRows.markLive]) and
 * the newest small group of events opens on its own ([TranscriptRows.openNewestGroup]) — and the two facts read over
 * the whole and told to each segment: the footer the reader's next message cut short, and the message a later run's
 * log carries again, which is drawn where it was first sent alone ([CoordinatorTranscript.repeatedMessages]). A segment whose items are the
 * instances they were last time (the repository keeps a turn's items when nothing about it moved) is answered from
 * the last presentation, the same row instances: what has not changed is neither rebuilt nor recomposed.
 *
 * One instance per screen; [present] is synchronized, so a presentation started before the next state landed
 * finishes on its own and the next one reads what it left.
 */
class TranscriptPresenter {

    /** What one state presents to: the items shown, the rows drawn, the reading of the chat, and the chat's goal. */
    class Presented(
        val items: List<TimelineItem>,
        val rows: List<TranscriptRow>,
        /** The chat read as a coordinator's, by the caller's word or by the content (see [CoordinatorTranscript.hasCoordinatorContent]). */
        val coordinatorMode: Boolean,
        /** The goal the transcript leaves the chat with (see [GoalTranscript.derive]). */
        val goal: Goal? = null,
        /** How many segments were cut afresh for this presentation, and how many were the last presentation's. */
        val segmentsBuilt: Int = 0,
        val segmentsReused: Int = 0,
        /** What the subagent rows say about the workers across every turn (see [SubagentRows.index]). */
        val subagents: SubagentRows.Index = SubagentRows.Index.EMPTY,
    ) {
        companion object {
            val EMPTY = Presented(emptyList(), emptyList(), coordinatorMode = false)
        }
    }

    /** One segment as last presented: its source items, what they presented to, and the rows cut from those. */
    private class Segment(
        val source: List<TimelineItem>,
        val mode: Boolean,
        /** The footers of this segment the reader's next message interrupted (see [TranscriptRows.interruptedFooters]). */
        val interrupted: Set<String>,
        /** The message calls of this segment that repeat one drawn earlier in the transcript (see [CoordinatorTranscript.repeatedMessages]). */
        val repeats: Set<String>,
        val presented: List<TimelineItem>,
        val rows: List<TranscriptRow>,
        val hasCoordinatorContent: Boolean,
    ) {
        /** Whether the segment's first item is [item] or stands for the same message: the same segment, changed or not. */
        fun startsLike(item: TimelineItem): Boolean {
            val first = source.first()
            return first === item || (first.javaClass === item.javaClass && first.id == item.id)
        }

        /**
         * Whether the segment presents `items[from, to)`: the same instances, or — when a rebuild minted new
         * instances for the same facts, as the default path's builder does for every prompt and footer — items that
         * equal them. The equality is cheap where it matters: a group's steps and a message's text are the very
         * instances the trace or the transcript holds, so their comparison is the identity check `equals` starts with.
         */
        fun matches(items: List<TimelineItem>, from: Int, to: Int, mode: Boolean): Boolean = this.mode == mode && holds(items, from, to)

        /** Whether the segment's source items are `items[from, to)`, as [matches] reads them, whatever the mode. */
        fun holds(items: List<TimelineItem>, from: Int, to: Int): Boolean {
            if (to - from != source.size) return false
            for (i in source.indices) {
                val mine = source[i]
                val theirs = items[from + i]
                if (mine !== theirs && !(mine.javaClass === theirs.javaClass && mine == theirs)) return false
            }
            return true
        }

        /** The items of [source] the goal is read from (see [GoalTranscript.goalItems]), read once. */
        val goalItems: List<TimelineItem> by lazy(LazyThreadSafetyMode.NONE) { GoalTranscript.goalItems(source) }

        /** What [rows] tell the subagent index (see [SubagentRows.marks]), read once. */
        val marks: List<SubagentRows.Mark> by lazy(LazyThreadSafetyMode.NONE) { SubagentRows.marks(rows) }

        /** Where in [rows] the newest stretch holding a group of events is, -1 for none (see [TranscriptRows.newestEventsStretch]); read once. */
        val newestEvents: Int by lazy(LazyThreadSafetyMode.NONE) { TranscriptRows.newestEventsStretch(rows, 0, rows.size) }

        /** Whether the segment was cut for the same two facts read over the whole. */
        fun cutFor(interrupted: Set<String>, repeats: Set<String>): Boolean {
            if (this.interrupted.size != interrupted.size || this.repeats.size != repeats.size) return false
            for (id in this.interrupted) if (id !in interrupted) return false
            for (key in this.repeats) if (key !in repeats) return false
            return true
        }

        /** Every key a call or an item of [source] could be left out under, read once (see [candidateKeys]). */
        private var keys: List<String>? = null

        /** The keys [all] leaves out among the segment's calls and items: [repeatsWithin] over [source]. */
        fun repeatsIn(all: CoordinatorTranscript.LeftOutScan): Set<String> {
            if (all.isEmpty) return emptySet()
            val keys = keys ?: candidateKeys(source, 0, source.size).also { keys = it }
            return leftOutAmong(keys, all)
        }
    }

    private var segments: List<Segment> = emptyList()

    /** True once this presenter has presented something: a screen reopening on it has rows to draw on its first frame. */
    val isWarm: Boolean get() = segments.isNotEmpty()

    /** The last tail passes' answers, so an unchanged tail keeps its row instances. */
    private var liveInput: TranscriptRow.Stretch? = null
    private var liveOutput: TranscriptRow.Stretch? = null
    private var openedInput: TranscriptRow.Stretch? = null
    private var openedOutput: TranscriptRow.Stretch? = null
    private var failureInput: TranscriptRow.Stretch? = null
    private var failureOutput: List<TranscriptRow>? = null

    /**
     * Presents [items]. [coordinatorMode] is the word of the list or the record; the content decides too, as the
     * screen has always read it. [runActive] marks the newest stretch as still being written.
     */
    @Synchronized
    fun present(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean): Presented {
        val startedAt = System.nanoTime()
        again(items, coordinatorMode, runActive)?.let { presented ->
            TranscriptPerf.focused?.presenterRun(System.nanoTime() - startedAt, built = 0, reused = presented.segmentsReused)
            return presented
        }
        val interruptedAll = TranscriptRows.interruptedFooters(items)
        // The other fact that crosses a turn: a message — or the whole activity — a later turn's log carries again is
        // the earlier turn's, and is drawn there alone (see CoordinatorTranscript.repeatedMessages, replayedActivity).
        // Read over the whole, told per segment.
        val repeatsAll = leftOut(items)
        val previous = segments
        // Whether the chat is a coordinator's by its content: the mode shapes every segment. When nothing said so
        // before the cutting, the segments are cut as a plain chat's and each one's content is read as it is reached
        // (a segment kept from the last presentation says what it said); one that says coordinator starts the
        // cutting again in that mode.
        val known = if (coordinatorMode) true else modeBeforeCutting(items, previous)
        val cutting = cutAll(items, previous, known ?: false, interruptedAll, repeatsAll, readContent = known == null)
            ?: run {
                contentSaid = true
                cutAll(items, previous, mode = true, interruptedAll, repeatsAll, readContent = false)!!
            }
        val mode = cutting.mode
        val next = cutting.segments
        val built = cutting.built
        val reused = cutting.reused
        segments = next
        val presentedItems: List<TimelineItem>
        val rawRows: List<TranscriptRow>
        if (next.size == 1) {
            presentedItems = next[0].presented
            rawRows = next[0].rows
        } else {
            presentedItems = ArrayList<TimelineItem>(next.sumOf { it.presented.size }).apply { next.forEach { addAll(it.presented) } }
            rawRows = ArrayList<TranscriptRow>(next.sumOf { it.rows.size }).apply { next.forEach { addAll(it.rows) } }
        }
        val rows = tailPasses(rawRows, runActive)
        // The goal and the subagent index read the whole in order: each segment's part is kept with it, and read again.
        val fold = GoalTranscript.Fold()
        for (segment in next) segment.goalItems.let { goalItems -> for (i in goalItems.indices) fold.add(goalItems[i]) }
        val goal = fold.goal
        val index = subagentIndex(next, rows).let { if (it == subagents) subagents else it.also { fresh -> subagents = fresh } }
        TranscriptPerf.focused?.presenterRun(System.nanoTime() - startedAt, built = built, reused = reused)
        return Presented(presentedItems, rows, mode, goal, segmentsBuilt = built, segmentsReused = reused, subagents = index)
            .also { last = Last(items.toList(), coordinatorMode, runActive, it) }
    }

    /** The last presentation's input — its items copied, so a caller's list changed in place does not match — and its answer. */
    private class Last(val items: List<TimelineItem>, val coordinatorMode: Boolean, val runActive: Boolean, var presented: Presented)

    private var last: Last? = null

    /**
     * The last answer when the input is the last one — the same item instances, mode and liveness, as a state that
     * moved only a flag (loading older, the queue) gives — else null. The passes of [present] read nothing but these
     * three and this presenter's state, which a presentation of the same input leaves as it found it; an input
     * [present] comes to read must join this check. The answer says no segment was cut, as none was.
     */
    private fun again(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean): Presented? {
        val last = last ?: return null
        if (last.coordinatorMode != coordinatorMode || last.runActive != runActive || last.items.size != items.size) return null
        for (i in items.indices) if (last.items[i] !== items[i]) return null
        val presented = last.presented
        if (presented.segmentsBuilt == 0) return presented
        return Presented(presented.items, presented.rows, presented.coordinatorMode, presented.goal, segmentsBuilt = 0, segmentsReused = segments.size, subagents = presented.subagents)
            .also { last.presented = it }
    }

    /** The last presentation's index, kept while it says the same so the rows reading it are not recomposed. */
    private var subagents: SubagentRows.Index = SubagentRows.Index.EMPTY

    /** The segments cut for one presentation, the mode they were cut in, and how many were cut afresh or kept. */
    private class Cutting(val segments: List<Segment>, val mode: Boolean, val built: Int, val reused: Int)

    /**
     * [items] cut into segments in [mode], each one the last presentation's where [previous] has it. With
     * [readContent], [mode] is a plain chat's until the content says otherwise: the cutting stops, with null, at the
     * first segment that holds a coordinator's call (see [CoordinatorTranscript.hasCoordinatorContent]).
     */
    private fun cutAll(
        items: List<TimelineItem>,
        previous: List<Segment>,
        mode: Boolean,
        interruptedAll: Set<String>,
        repeatsAll: CoordinatorTranscript.LeftOutScan,
        readContent: Boolean,
    ): Cutting? {
        val next = ArrayList<Segment>(previous.size + 1)
        var built = 0
        var reused = 0
        var p = 0
        var start = 0
        while (start < items.size) {
            val end = segmentEnd(items, start)
            val interrupted = interruptedWithin(items, start, end, interruptedAll)
            // The last presentation's segments are in order, as these are: the candidate is the next one not yet
            // matched, and a segment that moved (a page inserted above) is found by scanning on.
            var match: Segment? = null
            var repeats: Set<String>? = null
            var q = p
            while (q < previous.size) {
                val candidate = previous[q]
                if (candidate.matches(items, start, end, mode)) {
                    // The candidate's items are these: the keys they could be left out under are the candidate's.
                    val within = candidate.repeatsIn(repeatsAll).also { repeats = it }
                    if (candidate.cutFor(interrupted, within)) { match = candidate; p = q + 1; break }
                }
                // The candidate starts where this segment does but differs (a turn that grew or was completed): it
                // is this segment's earlier self, and nothing after it can be this segment either.
                if (candidate.startsLike(items[start])) { p = q + 1; break }
                q++
            }
            if (match != null) {
                if (readContent && match.hasCoordinatorContent) return null
                next += match
                reused++
            } else {
                // The segment's source items are kept as the segment's own copy only when they were not reused, so
                // the presentation holds no second copy of what it was given.
                val source = items.subList(start, end)
                val content = CoordinatorTranscript.hasCoordinatorContent(source)
                if (readContent && content) return null
                next += cut(source, mode, interrupted, repeats ?: repeatsWithin(items, start, end, repeatsAll), content)
                built++
            }
            start = end
        }
        return Cutting(next, mode, built, reused)
    }

    /**
     * Whether the chat is a coordinator's by its content, as far as it is known before the items are cut: a segment
     * that said so last time and is still among the items says so still (its groups are the instances it read), and
     * once a chat has been read as a coordinator's by its content it stays one, since the tool calls that made it
     * so are in a turn that does not go away. Null when the content has to be read: [cutAll] reads it.
     */
    private fun modeBeforeCutting(items: List<TimelineItem>, previous: List<Segment>): Boolean? {
        // A transcript emptied in place (Reload transcript) is read afresh, as the screen always read it.
        if (items.isEmpty()) { contentSaid = false; return false }
        if (contentSaid) return true
        for (segment in previous) {
            if (!segment.hasCoordinatorContent) continue
            val first = segment.source.first()
            val at = items.indexOfFirst { it === first }
            if (at >= 0 && at + segment.source.size <= items.size && segment.source.indices.all { segment.source[it] === items[at + it] }) return true
        }
        return null
    }

    /**
     * [SubagentRows.index] over [rows]: the kept marks of every segment before the first row the tail passes
     * replaced ([tailFrom]), and the rows from that segment on read afresh.
     */
    private fun subagentIndex(segments: List<Segment>, rows: List<TranscriptRow>): SubagentRows.Index {
        val indexer = SubagentRows.Indexer()
        var at = 0
        for (segment in segments) {
            if (at + segment.rows.size > tailFrom) break
            indexer.addAll(segment.marks)
            at += segment.rows.size
        }
        indexer.visit(rows, at, rows.size)
        return indexer.index()
    }

    /** The content said the chat is a coordinator's once this presentation's life: it is not asked again. */
    private var contentSaid = false

    /** Where the segment starting at [start] ends: at the next user message, or the end. */
    private fun segmentEnd(items: List<TimelineItem>, start: Int): Int {
        var i = start + 1
        while (i < items.size && items[i] !is UserMessage) i++
        return i
    }

    private fun interruptedWithin(items: List<TimelineItem>, from: Int, to: Int, all: Set<String>): Set<String> {
        if (all.isEmpty()) return emptySet()
        var found: MutableSet<String>? = null
        for (i in from until to) {
            val item = items[i]
            if (item is RunFooter && item.id in all) (found ?: HashSet<String>().also { found = it }) += item.id
        }
        return found ?: emptySet()
    }

    /**
     * The scan of [CoordinatorTranscript.leftOut] over [scanned] — the items up to the newest turn's runs, ending at a
     * footer — kept so a presentation reads only the runs after them while those items stay as they were.
     */
    private var scanBase: CoordinatorTranscript.LeftOutScan? = null
    private val scanned = ArrayList<TimelineItem>()

    /** [CoordinatorTranscript.leftOut] over [items], read on from the kept scan where [items] begin with what it read. */
    private fun leftOut(items: List<TimelineItem>): CoordinatorTranscript.LeftOutScan {
        var from = scanned.size
        if (from > items.size || (0 until from).any { !same(scanned[it], items[it]) }) {
            scanBase = null
            scanned.clear()
            from = 0
        }
        // The newest turn is the one that moves: the runs before it are read into the kept scan, once.
        var until = items.indexOfLast { it is UserMessage }.coerceAtLeast(0)
        while (until > from && items[until - 1] !is RunFooter) until--
        if (until > from) {
            try {
                (scanBase ?: CoordinatorTranscript.LeftOutScan().also { scanBase = it }).scan(items, from, until)
            } catch (e: RuntimeException) {
                // A scan that throws has read part of what it was given: the kept scan is read afresh next time.
                scanBase = null
                scanned.clear()
                throw e
            }
            scanned.addAll(items.subList(from, until))
            from = until
        }
        return CoordinatorTranscript.LeftOutScan(scanBase).also { it.scan(items, from, items.size) }
    }

    private fun same(a: TimelineItem, b: TimelineItem): Boolean = a === b || (a.javaClass === b.javaClass && a == b)

    /** The keys [all] leaves out (see [CoordinatorTranscript.leftOut]) that name a call or an item of `items[from, to)`. */
    private fun repeatsWithin(items: List<TimelineItem>, from: Int, to: Int, all: CoordinatorTranscript.LeftOutScan): Set<String> =
        if (all.isEmpty) emptySet() else leftOutAmong(candidateKeys(items, from, to), all)

    private fun cut(source: List<TimelineItem>, mode: Boolean, interrupted: Set<String>, repeats: Set<String>, hasCoordinatorContent: Boolean): Segment {
        val items = source.toList()
        val presented = GoalTranscript.lift(CoordinatorTranscript.present(items, mode, repeats))
        val rows: List<TranscriptRow> = TranscriptRows.cut(presented, mode, interrupted)
        // The summaries the rows carry are computed here, off the main thread, rather than on their first composition.
        rows.forEach { row ->
            when (row) {
                is TranscriptRow.Stretch -> row.summary.also {
                    row.entries.forEach { entry ->
                        when (entry) {
                            is TranscriptRow.Entry.Events -> entry.group.summary
                            // A thought's pieces, for when it is open: cut anew on every delta while it is written.
                            is TranscriptRow.Entry.Thought -> entry.parts
                            else -> Unit
                        }
                    }
                }
                is TranscriptRow.Events -> row.summary
                else -> Unit
            }
        }
        return Segment(items, mode, interrupted, repeats, presented, rows, hasCoordinatorContent)
    }

    /**
     * The three passes that read the whole: the newest stretch live while the run writes, the failure of the newest
     * run lifted out to its own row while the conversation has not moved past it, the newest small group of events
     * open. Each remembers its last answer, so a tail that has not changed keeps its row instances. The rows before
     * [tailFrom] are the ones they were given, in the same places.
     */
    private fun tailPasses(rows: List<TranscriptRow>, runActive: Boolean): List<TranscriptRow> {
        tailFrom = Int.MAX_VALUE
        var out: MutableList<TranscriptRow>? = null
        if (runActive) {
            val last = rows.indexOfLast { it is TranscriptRow.Stretch }
            if (last >= 0 && TranscriptRows.isLiveCandidate(rows, last)) {
                val stretch = rows[last] as TranscriptRow.Stretch
                val live = if (liveInput === stretch) liveOutput!! else stretch.copy(live = true).also { liveInput = stretch; liveOutput = it }
                out = rows.toMutableList().also { it[last] = live }
                tailFrom = last
            }
        } else {
            TranscriptRows.currentFailureStretch(rows)?.let { (index, stretch) ->
                // The lift's answer for this stretch: the stretch without its line (or nothing, for a footer alone) and the row.
                val lifted = if (failureInput === stretch) failureOutput!! else TranscriptRows.liftCurrentFailure(listOf(stretch), runActive = false).also { failureInput = stretch; failureOutput = it }
                out = rows.toMutableList().also { it.removeAt(index); it.addAll(index, lifted) }
                tailFrom = index
            }
        }
        val opened = newestGroupToOpen(out ?: rows) ?: return out ?: rows
        val (index, stretch, entryIndex) = opened
        val replacement = if (openedInput === stretch) openedOutput!! else TranscriptRows.withGroupOpen(stretch, entryIndex).also { openedInput = stretch; openedOutput = it }
        tailFrom = minOf(tailFrom, index)
        return (out ?: rows.toMutableList()).also { it[index] = replacement }
    }

    /** Where the last [tailPasses] first replaced a row it was given; [Int.MAX_VALUE] when it replaced none. */
    private var tailFrom = Int.MAX_VALUE

    /**
     * [TranscriptRows.newestGroupToOpen] over [rows], the segments' rows with the tail passes' so far: the rows from
     * the segment the passes first replaced one in ([tailFrom]) are read, and before them each segment says where
     * its newest group of events is.
     */
    private fun newestGroupToOpen(rows: List<TranscriptRow>): Triple<Int, TranscriptRow.Stretch, Int>? {
        val segments = segments
        var s = 0
        var at = 0
        while (s < segments.size && at + segments[s].rows.size <= tailFrom) { at += segments[s].rows.size; s++ }
        var r = TranscriptRows.newestEventsStretch(rows, at, rows.size)
        while (r < 0 && s > 0) {
            s--
            at -= segments[s].rows.size
            val within = segments[s].newestEvents
            if (within >= 0) r = at + within
        }
        return TranscriptRows.groupToOpen(rows, r)
    }
}

/** Every key a call or an item of `items[from, to)` could be left out under (see [CoordinatorTranscript.leftOut]), in order. */
private fun candidateKeys(items: List<TimelineItem>, from: Int, to: Int): List<String> {
    val out = ArrayList<String>(to - from)
    for (i in from until to) {
        val item = items[i]
        out += CoordinatorTranscript.itemKey(item)
        if (item !is ActivityGroup) continue
        for (step in item.steps) if (step is ToolCall) out += CoordinatorTranscript.messageKey(item, step)
    }
    return out
}

/** The [keys] that [all] leaves out. */
private fun leftOutAmong(keys: List<String>, all: CoordinatorTranscript.LeftOutScan): Set<String> {
    var found: MutableSet<String>? = null
    for (key in keys) if (all.leavesOut(key)) (found ?: HashSet<String>().also { found = it }) += key
    return found ?: emptySet()
}
