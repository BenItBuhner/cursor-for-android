package com.cursorforandroid.domain

import com.cursorforandroid.util.TimeFormat

/**
 * One row of the transcript as the list draws it. The messages — the user's prompts, the turns Cursor injected, an
 * agent's replies, a Project coordinator's `SendMessage` updates — are rows of their own; everything the agent did
 * between two of them is one [Stretch]: a summary line ("Worked 1m 48s · 4 edits · 1 thought") that opens onto the
 * whole sequence, verbatim and in order — its thoughts, its tool calls with the detail each opens onto, its
 * working notes, its run's footer. A worker's card, the pictures a step produced and the question a run is paused
 * on are never behind the summary: they stay rows of their own, where they were.
 */
sealed interface TranscriptRow {
    /** Stable across rebuilds while the run streams: the list's key and the row's saved-state key. */
    val key: String

    /** An item drawn as itself: a prompt, an agent's reply, a notice. */
    data class Item(val item: TimelineItem) : TranscriptRow {
        override val key: String get() = item.id
    }

    /**
     * A turn Cursor injected — a subagent's report, a subscribed pull request's change, a timer — as its one-line
     * row. [count] is above one when the same notice arrived that many times in a row ("#12 · synchronize ×2").
     */
    data class Event(val notification: SystemNotification, val count: Int = 1) : TranscriptRow {
        override val key: String get() = notification.id
        val line: EventLine get() = EventLine.of(notification)
    }

    /**
     * Consecutive injected turns the agent answered with nothing the reader would see — no message, no worker's
     * card, no question, no picture — behind one line ("14 events · 9 GitHub · 5 subagents · 2h span") that opens
     * onto every one of them in order, each with its own detail, and the work done between them where there was
     * any. [startsOpen] for the newest group when it has fewer than three events: too few to be worth a line of
     * their own, and the most recent are what the reader came for.
     */
    data class Events(val rows: List<TranscriptRow>, val startsOpen: Boolean = false) : TranscriptRow {
        override val key: String get() = "events:${rows.first().key}"

        val events: List<Event> get() = rows.filterIsInstance<Event>()

        /** How many notices the group holds, a repeated one counted each time it came. */
        val count: Int get() = events.sumOf { it.count }

        val summary: EventGroupSummary by lazy { EventGroupSummary.of(this) }
    }

    /** A Project coordinator's message to the user, drawn as a reply. */
    data class Message(val group: ActivityGroup, val call: ToolCall) : TranscriptRow {
        override val key: String get() = "${group.id}:${call.callId}"
    }

    /** The card of a worker a coordinator created. */
    data class Worker(val group: ActivityGroup, val call: ToolCall) : TranscriptRow {
        override val key: String get() = "${group.id}:${call.callId}:worker"
    }

    /** The pictures and recordings the calls of a stretch produced, shown whether or not the stretch is open. */
    data class Media(val group: ActivityGroup) : TranscriptRow {
        override val key: String get() = "${group.id}:media"
    }

    /** The question a run is paused on, as its card. */
    data class Question(val group: ActivityGroup, val call: ToolCall) : TranscriptRow {
        override val key: String get() = "${group.id}:${call.callId}:question"
    }

    /**
     * Everything between two messages, behind one summary. [live] while the run is still writing into it: the
     * summary then reads "Working" and shimmers. A stretch of one entry is drawn as that entry, not as a summary of it.
     */
    data class Stretch(val entries: List<Entry>, val live: Boolean = false) : TranscriptRow {
        override val key: String get() = "stretch:${entries.first().key}"

        val single: Entry? get() = entries.singleOrNull()

        val summary: StretchSummary by lazy { StretchSummary.of(this) }

        /** The entries the open stretch lists: the footer is not among them, the summary already carries it. */
        val listed: List<Entry> get() = entries.filterNot { it is Entry.Footer }
    }

    /** One step of a [Stretch], in the order it happened. */
    sealed interface Entry {
        val key: String

        data class Thought(val block: ThinkingBlock, override val key: String) : Entry
        data class Call(val call: ToolCall, override val key: String) : Entry

        /** A coordinator's working note: the prose it wrote between tool calls, which is not its message. */
        data class Note(val message: AssistantMessage) : Entry {
            override val key: String get() = message.id
        }

        data class Footer(val footer: RunFooter) : Entry {
            override val key: String get() = footer.id
        }

        data class Line(val row: SummaryRow) : Entry {
            override val key: String get() = row.id
        }
    }
}

/**
 * The summary line of a [TranscriptRow.Stretch]: the verb — "Worked 1m 48s" from the run's footer, "Working" while
 * the run still writes, else the first count — then the counts of what the stretch holds, largest kinds first
 * ("4 edits · 2 files · 1 thought"), the line counts of its edits set apart.
 */
data class StretchSummary(val action: String, val details: String?, val lineStats: String?, val busy: Boolean) {
    /** The whole line, for tests and for the diagnostics. */
    val text: String get() = listOfNotNull(action, details).joinToString(" \u00B7 ")

    companion object {
        /** How many counts the line carries; a failure is always among them. */
        private const val MAX_COUNTS = 3

        fun of(stretch: TranscriptRow.Stretch): StretchSummary {
            val all = stretch.entries.filterIsInstance<TranscriptRow.Entry.Call>().map { it.call }
            // A call that failed is counted once, as a failure, not as the edit or read it did not manage.
            val failed = all.count { it.isError }
            val calls = all.filterNot { it.isError }
            val work = WorkSummary.of(calls)
            val thoughts = stretch.entries.count { it is TranscriptRow.Entry.Thought && it.block.text.isNotBlank() }
            val notes = stretch.entries.count { it is TranscriptRow.Entry.Note && it.message.markdown.isNotBlank() }
            val agents = work.taskCalls + calls.count { it.kind == ToolKind.Coordinator && it.payload is ToolPayload.WorkerAction }
            // The calls no count above names — a mode switch, a to-do update, a plan — are steps; a question has its card.
            val steps = calls.count { it.kind == ToolKind.Other || it.kind == ToolKind.Todo || it.kind == ToolKind.Plan || (it.kind == ToolKind.Coordinator && it.payload !is ToolPayload.WorkerAction) }
            val counts = listOfNotNull(
                plural(work.edits + work.deletes, "edit"),
                plural(work.files.size + work.directories.size, "file"),
                plural(work.searches + work.fetches, "search", "searches"),
                plural(work.commands, "command"),
                plural(work.mcpToolCalls + work.lints, "tool"),
                plural(work.images, "image"),
                plural(agents, "agent"),
                plural(thoughts, "thought"),
                plural(notes, "note"),
                plural(steps, "step"),
            )
            val failure = if (failed > 0) "$failed failed" else null
            val footer = stretch.entries.filterIsInstance<TranscriptRow.Entry.Footer>().lastOrNull()?.footer
            val action = when {
                footer != null -> footerLabel(footer)
                stretch.live -> "Working"
                else -> counts.firstOrNull() ?: failure ?: "Worked"
            }
            val rest = if (footer != null || stretch.live) counts else counts.drop(1)
            val details = (rest.take(MAX_COUNTS) + listOfNotNull(failure.takeIf { action != failure })).joinToString(" \u00B7 ").ifEmpty { null }
            return StretchSummary(action, details, work.lineStats, busy = stretch.live)
        }

        /** The footer's own line: "Worked 1m 48s", "Failed after 2m 3s", "Cancelled", "Expired". */
        fun footerLabel(footer: RunFooter): String {
            val ending = when (footer.status) {
                RunStatus.ERROR -> "Failed"
                RunStatus.CANCELLED -> "Cancelled"
                RunStatus.EXPIRED -> "Expired"
                else -> null
            }
            val duration = TimeFormat.duration(footer.durationMs)
            return when {
                duration != null -> "${ending?.let { "$it after" } ?: "Worked"} $duration"
                else -> ending ?: "Worked"
            }
        }

        private fun plural(count: Int, one: String, many: String = "${one}s"): String? =
            if (count <= 0) null else "$count ${if (count == 1) one else many}"
    }
}

/**
 * What one line of an injected turn's row says, read off the notification: the glyph's kind, the subject — a
 * subagent's title, a pull request's number — what happened to it, and who did it. "Subagent completed · Hand & Arm
 * Renders" reads as "Hand & Arm Renders · completed"; "GitHub notification · #64 · opened · BenItBuhner" as "#64 ·
 * opened · BenItBuhner"; a goal's row keeps its event first, "Goal continued · <objective>".
 */
data class EventLine(val source: Source, val subject: String, val verb: String?, val actor: String?) {
    /** What the row's glyph stands for, and what a group counts by. */
    enum class Source(val label: String, val plural: String) {
        GitHub("GitHub", "GitHub"),
        Subagent("subagent", "subagents"),
        Worker("worker", "workers"),
        Goal("goal", "goals"),
        Task("task", "tasks"),
        Timer("timer", "timers"),
        Other("other", "other"),
    }

    /** The line as one string, for tests, previews and copies. */
    val text: String get() = listOfNotNull(subject, verb, actor).joinToString(" \u00B7 ")

    companion object {
        private const val GITHUB_TITLE = "GitHub notification"
        private const val OTHER_TITLE = "System notification"
        private const val SEPARATOR = " \u00B7 "

        fun of(notification: SystemNotification): EventLine {
            val summary = notification.summary?.trim()?.takeIf { it.isNotEmpty() }
            val title = notification.title.trim()
            return when {
                title == GITHUB_TITLE -> {
                    // Made from the tag's attributes as "#59 · synchronize · cursor[bot]"; a body-only one is its first line.
                    val parts = summary?.split(SEPARATOR)?.map { it.trim() }.orEmpty()
                    if (parts.size >= 2) EventLine(Source.GitHub, parts[0], parts[1], parts.getOrNull(2))
                    else EventLine(Source.GitHub, summary ?: "Pull request", null, null)
                }
                // A goal's row is the event — "Goal set", "Goal continued", "Goal completed" — with the objective beside it,
                // dimmed: the objective is a sentence, not a name, and the event is what the reader scans for.
                notification.kind == SystemNotification.Kind.Goal -> EventLine(Source.Goal, title, null, summary)
                notification.kind == SystemNotification.Kind.Subagent || notification.kind == SystemNotification.Kind.Worker || notification.kind == SystemNotification.Kind.Task -> {
                    // "Subagent completed", "Worker failed", "Shell timed out": the noun is the glyph, the rest is the verb.
                    val source = when (notification.kind) {
                        SystemNotification.Kind.Subagent -> Source.Subagent
                        SystemNotification.Kind.Worker -> Source.Worker
                        else -> Source.Task
                    }
                    val noun = title.substringBefore(' ')
                    val verb = title.substringAfter(' ', "").ifEmpty { null }
                    EventLine(source, summary ?: noun, verb, null)
                }
                title.startsWith("Timer", ignoreCase = true) -> EventLine(Source.Timer, "Timer", summary ?: "fired", null)
                // "Slack notification · <first line>" reads as "<first line> · Slack"; a bare system notice is its line alone.
                else -> EventLine(Source.Other, summary ?: title, if (summary != null && title != OTHER_TITLE) title.removeSuffix(" notification").ifEmpty { null } else null, null)
            }
        }
    }
}

/**
 * The line of a collapsed [TranscriptRow.Events]: how many notices, then which kinds, most first ("14 events ·
 * 9 GitHub · 5 subagents"), then how long they span when the turns carry their times and it is a minute or more.
 */
data class EventGroupSummary(val count: String, val kinds: String?, val span: String?) {
    val text: String get() = listOfNotNull(count, kinds, span).joinToString(" \u00B7 ")

    companion object {
        private const val MAX_KINDS = 3

        fun of(group: TranscriptRow.Events): EventGroupSummary {
            val events = group.events
            val total = group.count
            val byKind = events.groupBy { it.line.source }.mapValues { (_, rows) -> rows.sumOf { it.count } }
            val kinds = byKind.entries
                .sortedWith(compareByDescending<Map.Entry<EventLine.Source, Int>> { it.value }.thenBy { it.key.ordinal })
                .take(MAX_KINDS)
                .map { (source, n) -> "$n ${if (n == 1) source.label else source.plural}" }
                .joinToString(" \u00B7 ")
                .ifEmpty { null }
            val times = events.mapNotNull { it.notification.timestampMillis }
            val span = if (times.size >= 2) spanLabel(times.max() - times.min()) else null
            return EventGroupSummary("$total ${if (total == 1) "event" else "events"}", kinds, span)
        }

        /** "2h span", "1h 59m span", "18m span"; nothing under a minute, which is not a span worth a word. */
        private fun spanLabel(millis: Long): String? {
            val minutes = millis / 60_000L
            if (minutes < 1) return null
            val hours = minutes / 60
            val rest = minutes % 60
            val label = when {
                hours > 0 && rest > 0 -> "${hours}h ${rest}m"
                hours > 0 -> "${hours}h"
                else -> "${rest}m"
            }
            return "$label span"
        }
    }
}

/**
 * Cuts a transcript into [TranscriptRow]s. A message ends the stretch before it — the user's prompt, an injected
 * turn's row, a notice, an agent's reply, and in a coordinator's chat ([coordinatorMode]) the coordinator's message
 * to the user, which sits among the steps of its group; a coordinator's plain reply is a note inside the stretch
 * instead. A worker's card cuts the stretch the same way and stands on its own; a step's pictures and the question
 * a run is paused on follow the stretch they belong to as rows of their own. [runActive] marks the newest stretch
 * as still being written when the run is.
 */
object TranscriptRows {

    fun of(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean = false): List<TranscriptRow> {
        val rows = ArrayList<TranscriptRow>(items.size)
        val open = ArrayList<TranscriptRow.Entry>()
        // What follows the stretch as rows of its own once it closes: its pictures, then the question it waits on.
        val media = ArrayList<TranscriptRow>()
        val questions = ArrayList<TranscriptRow>()

        fun flush() {
            if (open.isNotEmpty()) {
                rows += TranscriptRow.Stretch(open.toList())
                open.clear()
            }
            rows += media
            rows += questions
            media.clear()
            questions.clear()
        }

        for (item in items) {
            when (item) {
                is UserMessage, is NoticeCard -> {
                    flush()
                    rows += TranscriptRow.Item(item)
                }
                is SystemNotification -> {
                    flush()
                    rows += TranscriptRow.Event(item)
                }
                is AssistantMessage -> if (coordinatorMode) {
                    open += TranscriptRow.Entry.Note(item)
                } else {
                    flush()
                    rows += TranscriptRow.Item(item)
                }
                is RunFooter -> {
                    // The footer closes its run: what follows belongs to the next one, whether or not a prompt heads it.
                    open += TranscriptRow.Entry.Footer(item)
                    flush()
                }
                is SummaryRow -> open += TranscriptRow.Entry.Line(item)
                is ActivityGroup -> {
                    val pictures = ArrayList<ToolCall>()
                    item.steps.forEachIndexed { index, step ->
                        when (step) {
                            is ThinkingBlock -> open += TranscriptRow.Entry.Thought(step, "${item.id}:thought-$index")
                            is ToolCall -> when {
                                step.payload is ToolPayload.CoordinatorMessage -> {
                                    // The coordinator's word to the user is a message, whatever else the group holds.
                                    flush()
                                    rows += TranscriptRow.Message(item, step)
                                }
                                (step.payload as? ToolPayload.WorkerAction)?.kind == ToolPayload.WorkerAction.Kind.Created -> {
                                    flush()
                                    rows += TranscriptRow.Worker(item, step)
                                }
                                else -> {
                                    open += TranscriptRow.Entry.Call(step, "${item.id}:${step.callId}")
                                    if (step.hasMedia) pictures += step
                                    if (step.pendingQuestion != null) questions += TranscriptRow.Question(item, step)
                                }
                            }
                        }
                    }
                    if (pictures.isNotEmpty()) media += TranscriptRow.Media(ActivityGroup("${item.id}:media", pictures))
                }
            }
        }
        flush()
        if (runActive) markLive(rows)
        return groupEvents(dedupe(rows))
    }

    /** The newest stretch is the one still being written, unless its run has already closed with a footer. */
    private fun markLive(rows: MutableList<TranscriptRow>) {
        val last = rows.indexOfLast { it is TranscriptRow.Stretch }
        if (last < 0) return
        val stretch = rows[last] as TranscriptRow.Stretch
        if (stretch.entries.any { it is TranscriptRow.Entry.Footer }) return
        if (rows.subList(last + 1, rows.size).any { it !is TranscriptRow.Media && it !is TranscriptRow.Question }) return
        rows[last] = stretch.copy(live = true)
    }

    /** The same notice arriving several times in a row — a pull request synchronized twice — is one row counted twice. */
    private fun dedupe(rows: List<TranscriptRow>): List<TranscriptRow> {
        val out = ArrayList<TranscriptRow>(rows.size)
        for (row in rows) {
            val previous = out.lastOrNull()
            if (row is TranscriptRow.Event && previous is TranscriptRow.Event && sameNotice(previous.notification, row.notification)) {
                out[out.size - 1] = previous.copy(count = previous.count + row.count)
            } else {
                out += row
            }
        }
        return out
    }

    private fun sameNotice(a: SystemNotification, b: SystemNotification): Boolean =
        a.kind == b.kind && a.title == b.title && a.summary == b.summary && a.agentId == b.agentId

    /**
     * Consecutive silent injected turns — an event row followed by nothing, or by stretches alone, up to the next
     * turn — become one [TranscriptRow.Events] when there are two or more of them. A turn that produced a message,
     * a worker's card, a question, a picture, or that is still running, is not silent: it stays as its rows and
     * ends the group before it. The newest group opens on its own when it holds fewer than three events.
     */
    private fun groupEvents(rows: List<TranscriptRow>): List<TranscriptRow> {
        if (rows.count { it is TranscriptRow.Event } < 2) return rows
        val out = ArrayList<TranscriptRow>(rows.size)
        var i = 0
        while (i < rows.size) {
            if (rows[i] !is TranscriptRow.Event) {
                out += rows[i]
                i++
                continue
            }
            val group = ArrayList<TranscriptRow>()
            var j = i
            while (j < rows.size && rows[j] is TranscriptRow.Event) {
                var k = j + 1
                while (k < rows.size && rows[k].let { it is TranscriptRow.Stretch && !it.live }) k++
                if (!silentTurnEndsAt(rows, k)) break
                group += rows.subList(j, k)
                j = k
            }
            if (group.count { it is TranscriptRow.Event } >= 2) {
                out += TranscriptRow.Events(group.toList())
                i = j
            } else {
                out += rows[i]
                i++
            }
        }
        val newest = out.indexOfLast { it is TranscriptRow.Events }
        if (newest >= 0) {
            val group = out[newest] as TranscriptRow.Events
            if (group.count < OPEN_BELOW) out[newest] = group.copy(startsOpen = true)
        }
        return out
    }

    /** Whether the turn whose rows end before [index] said nothing: the next row starts another turn, or there is none. */
    private fun silentTurnEndsAt(rows: List<TranscriptRow>, index: Int): Boolean {
        if (index >= rows.size) return true
        val next = rows[index]
        return next is TranscriptRow.Event || (next is TranscriptRow.Item && (next.item is UserMessage || next.item is NoticeCard))
    }

    /** A newest group with fewer events than this is shown open. */
    const val OPEN_BELOW = 3
}
