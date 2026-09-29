package com.cursorforandroid.domain

/*
 * The oracle for [IncrementalLeftOutTest]: what the transcript left out as said again, and the presenter that read
 * it, as they were before the scan was made resumable — the scan over the whole transcript on every presentation.
 * Kept verbatim (the presenter's class renamed, and reading [LegacyLeftOut]) so the incremental reading is held to
 * the rows, items and reuse the full scan gave, turn for turn — save the fix both scans share: a message key a replay
 * shares with a call of the run's own is not a replay's (see `ReplayedActivityDuplicateKeyTest`).
 */

/** [CoordinatorTranscript]'s `repeatedMessages`, `replayedActivity` and `leftOut` as they read before the scan was resumable. */
internal object LegacyLeftOut {
    fun repeatedMessages(items: List<TimelineItem>): Map<String, String> {
        var repeats: MutableMap<String, String>? = null
        var seen: MutableMap<String, String>? = null
        for (item in items) {
            if (item !is ActivityGroup) continue
            for (step in item.steps) {
                if (step !is ToolCall) continue
                val call = CoordinatorTranscript.reinterpret(step)
                val payload = call.payload as? ToolPayload.CoordinatorMessage ?: continue
                if (payload.missing && payload.message.isBlank()) continue
                val text = CoordinatorTranscript.normalize(payload.message)
                val names = listOfNotNull(
                    call.callId.takeIf { it.isNotBlank() }?.let { "call\u0000$it\u0000$text" },
                    payload.messageId?.takeIf { it.isNotBlank() }?.let { "message\u0000$it\u0000$text" },
                )
                if (names.isEmpty()) continue
                val original = names.firstNotNullOfOrNull { seen?.get(it) }
                if (original != null) {
                    (repeats ?: LinkedHashMap<String, String>().also { repeats = it })[CoordinatorTranscript.messageKey(item, call)] = original
                    continue
                }
                val known = seen ?: HashMap<String, String>().also { seen = it }
                names.forEach { known[it] = call.callId }
            }
        }
        return repeats ?: emptyMap()
    }

    fun leftOut(items: List<TimelineItem>): Set<String> {
        val messages = repeatedMessages(items)
        val replays = replayedActivity(items)
        return when {
            replays.isEmpty() -> messages.keys
            messages.isEmpty() -> replays.keys
            else -> messages.keys + replays.keys
        }
    }

    private fun callKey(call: ToolCall): String = "${call.callId}\u0000${call.name.lowercase()}\u0000${call.summary}\u0000${call.detail ?: ""}"

    fun replayedActivity(items: List<TimelineItem>): Map<String, String> {
        var out: MutableMap<String, String>? = null
        val seenCalls = HashMap<String, String>()
        val seenTexts = HashMap<String, String>()
        var start = 0
        var runNo = 0
        while (start < items.size) {
            var end = start
            while (end < items.size && items[end] !is RunFooter) end++
            val run = items.subList(start, minOf(end + 1, items.size))
            val runName = (run.lastOrNull() as? RunFooter)?.let { "run:${it.runId}" } ?: "run#$runNo"
            var own = false
            val replayed = HashSet<String>()
            val ownKeys = HashSet<String>()
            for (item in run) {
                if (item !is ActivityGroup) continue
                for (step in item.steps) {
                    if (step !is ToolCall || step.callId.isBlank()) continue
                    val key = callKey(step)
                    if (seenCalls.containsKey(key)) replayed += CoordinatorTranscript.messageKey(item, step) else { own = true; ownKeys += CoordinatorTranscript.messageKey(item, step) }
                }
            }
            replayed.removeAll(ownKeys)
            for (item in run) {
                when (item) {
                    is ActivityGroup -> {
                        val calls = item.steps.filterIsInstance<ToolCall>()
                        val gone = calls.filter { CoordinatorTranscript.messageKey(item, it) in replayed }
                        if (gone.isEmpty()) {
                            calls.forEach { seenCalls.putIfAbsent(callKey(it), runName) }
                            continue
                        }
                        val map = out ?: LinkedHashMap<String, String>().also { out = it }
                        if (gone.size == calls.size) {
                            map[CoordinatorTranscript.itemKey(item)] = seenCalls.getValue(callKey(gone.first()))
                        } else {
                            gone.forEach { map[CoordinatorTranscript.messageKey(item, it)] = seenCalls.getValue(callKey(it)) }
                            calls.filter { CoordinatorTranscript.messageKey(item, it) !in replayed }.forEach { seenCalls.putIfAbsent(callKey(it), runName) }
                        }
                    }
                    is AssistantMessage -> {
                        val text = CoordinatorTranscript.normalize(item.markdown)
                        if (text.isEmpty()) continue
                        val earlier = seenTexts[text]
                        if (earlier != null && !own && earlier != runName) {
                            (out ?: LinkedHashMap<String, String>().also { out = it })[CoordinatorTranscript.itemKey(item)] = earlier
                        } else {
                            seenTexts.putIfAbsent(text, runName)
                        }
                    }
                    else -> Unit
                }
            }
            start = end + 1
            runNo++
        }
        return out ?: emptyMap()
    }
}

class FullScanTranscriptPresenter {

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
        fun matches(items: List<TimelineItem>, from: Int, to: Int, mode: Boolean, interrupted: Set<String>, repeats: Set<String>): Boolean {
            if (this.mode != mode || to - from != source.size) return false
            for (i in source.indices) {
                val mine = source[i]
                val theirs = items[from + i]
                if (mine !== theirs && !(mine.javaClass === theirs.javaClass && mine == theirs)) return false
            }
            if (this.interrupted.size != interrupted.size || this.repeats.size != repeats.size) return false
            for (id in this.interrupted) if (id !in interrupted) return false
            for (key in this.repeats) if (key !in repeats) return false
            return true
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
        val interruptedAll = TranscriptRows.interruptedFooters(items)
        // The other fact that crosses a turn: a message — or the whole activity — a later turn's log carries again is
        // the earlier turn's, and is drawn there alone (see CoordinatorTranscript.repeatedMessages, replayedActivity).
        // Read over the whole, told per segment.
        val repeatsAll = LegacyLeftOut.leftOut(items)
        val previous = segments
        // Whether the chat is a coordinator's by its content, before anything is cut: the mode shapes every segment.
        val mode = coordinatorMode || contentSaysCoordinator(items, previous)
        val next = ArrayList<Segment>(previous.size + 1)
        var built = 0
        var reused = 0
        var p = 0
        var start = 0
        while (start < items.size) {
            val end = segmentEnd(items, start)
            val interrupted = interruptedWithin(items, start, end, interruptedAll)
            val repeats = repeatsWithin(items, start, end, repeatsAll)
            // The last presentation's segments are in order, as these are: the candidate is the next one not yet
            // matched, and a segment that moved (a page inserted above) is found by scanning on.
            var match: Segment? = null
            var q = p
            while (q < previous.size) {
                val candidate = previous[q]
                if (candidate.matches(items, start, end, mode, interrupted, repeats)) { match = candidate; p = q + 1; break }
                // The candidate starts where this segment does but differs (a turn that grew or was completed): it
                // is this segment's earlier self, and nothing after it can be this segment either.
                if (candidate.startsLike(items[start])) { p = q + 1; break }
                q++
            }
            if (match != null) {
                next += match
                reused++
            } else {
                // The segment's source items are kept as the segment's own copy only when they were not reused, so
                // the presentation holds no second copy of what it was given.
                next += cut(items.subList(start, end), mode, interrupted, repeats)
                built++
            }
            start = end
        }
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
        val goal = GoalTranscript.derive(items)
        val index = SubagentRows.index(rows).let { if (it == subagents) subagents else it.also { fresh -> subagents = fresh } }
        TranscriptPerf.focused?.presenterRun(System.nanoTime() - startedAt, built = built, reused = reused)
        return Presented(presentedItems, rows, mode, goal, segmentsBuilt = built, segmentsReused = reused, subagents = index)
    }

    /** The last presentation's index, kept while it says the same so the rows reading it are not recomposed. */
    private var subagents: SubagentRows.Index = SubagentRows.Index.EMPTY

    /**
     * The chat's content says coordinator: a segment that said so last time and is still among the items says so
     * still (its groups are the instances it read); otherwise the content is read — once a chat has been read as a
     * coordinator's by its content it stays one, since the tool calls that made it so are in a turn that does not go away.
     */
    private fun contentSaysCoordinator(items: List<TimelineItem>, previous: List<Segment>): Boolean {
        // A transcript emptied in place (Reload transcript) is read afresh, as the screen always read it.
        if (items.isEmpty()) { contentSaid = false; return false }
        if (contentSaid) return true
        for (segment in previous) {
            if (!segment.hasCoordinatorContent) continue
            val first = segment.source.first()
            val at = items.indexOfFirst { it === first }
            if (at >= 0 && at + segment.source.size <= items.size && segment.source.indices.all { segment.source[it] === items[at + it] }) return true
        }
        return CoordinatorTranscript.hasCoordinatorContent(items).also { if (it) contentSaid = true }
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

    /** The keys of [all] (see [CoordinatorTranscript.leftOut]) that name a call or an item of `items[from, to)`. */
    private fun repeatsWithin(items: List<TimelineItem>, from: Int, to: Int, all: Set<String>): Set<String> {
        if (all.isEmpty()) return emptySet()
        var found: MutableSet<String>? = null
        for (i in from until to) {
            val item = items[i]
            val whole = CoordinatorTranscript.itemKey(item)
            if (whole in all) (found ?: HashSet<String>().also { found = it }) += whole
            if (item !is ActivityGroup) continue
            for (step in item.steps) {
                if (step !is ToolCall) continue
                val key = CoordinatorTranscript.messageKey(item, step)
                if (key in all) (found ?: HashSet<String>().also { found = it }) += key
            }
        }
        return found ?: emptySet()
    }

    private fun cut(source: List<TimelineItem>, mode: Boolean, interrupted: Set<String>, repeats: Set<String>): Segment {
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
        return Segment(items, mode, interrupted, repeats, presented, rows, CoordinatorTranscript.hasCoordinatorContent(items))
    }

    /**
     * The three passes that read the whole: the newest stretch live while the run writes, the failure of the newest
     * run lifted out to its own row while the conversation has not moved past it, the newest small group of events
     * open. Each remembers its last answer, so a tail that has not changed keeps its row instances.
     */
    private fun tailPasses(rows: List<TranscriptRow>, runActive: Boolean): List<TranscriptRow> {
        var out: MutableList<TranscriptRow>? = null
        if (runActive) {
            val last = rows.indexOfLast { it is TranscriptRow.Stretch }
            if (last >= 0 && TranscriptRows.isLiveCandidate(rows, last)) {
                val stretch = rows[last] as TranscriptRow.Stretch
                val live = if (liveInput === stretch) liveOutput!! else stretch.copy(live = true).also { liveInput = stretch; liveOutput = it }
                out = rows.toMutableList().also { it[last] = live }
            }
        } else {
            TranscriptRows.currentFailureStretch(rows)?.let { (index, stretch) ->
                // The lift's answer for this stretch: the stretch without its line (or nothing, for a footer alone) and the row.
                val lifted = if (failureInput === stretch) failureOutput!! else TranscriptRows.liftCurrentFailure(listOf(stretch), runActive = false).also { failureInput = stretch; failureOutput = it }
                out = rows.toMutableList().also { it.removeAt(index); it.addAll(index, lifted) }
            }
        }
        val opened = TranscriptRows.newestGroupToOpen(out ?: rows) ?: return out ?: rows
        val (index, stretch, entryIndex) = opened
        val replacement = if (openedInput === stretch) openedOutput!! else TranscriptRows.withGroupOpen(stretch, entryIndex).also { openedInput = stretch; openedOutput = it }
        return (out ?: rows.toMutableList()).also { it[index] = replacement }
    }
}
