package com.cursorforandroid.domain

import com.cursorforandroid.util.TimeFormat

/**
 * One row of the transcript as the list draws it. The messages — the user's prompts, the turns Cursor injected, an
 * agent's replies, a Project coordinator's `SendMessage` updates — are rows of their own; everything the agent did
 * between two of them is one [Stretch]: a summary line ("Worked 1m 48s · 4 edits · 1 thought") that opens onto the
 * whole sequence, verbatim and in order — its thoughts, its tool calls with the detail each opens onto (a subagent's
 * row among them, as Cursor's desktop keeps a task's row inside its work group), its working notes, its run's
 * footer. The pictures a step produced and the question a run is paused on are never behind the summary: they stay
 * rows of their own, where they were.
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

    /** The pictures and recordings the calls of a stretch produced, shown whether or not the stretch is open. */
    data class Media(val group: ActivityGroup) : TranscriptRow {
        override val key: String get() = "${group.id}:media"
    }

    /** The question a run is paused on, as its card. */
    data class Question(val group: ActivityGroup, val call: ToolCall) : TranscriptRow {
        override val key: String get() = "${group.id}:${call.callId}:question"
    }

    /**
     * The failure of the newest run, which the conversation has not moved past: one compact line of its own after
     * the run's stretch — "Run failed · <the server's reason> · <when>" — for a run the server's status says failed
     * ([RunFooter.isFailure]) with no newer run or turn after it and nothing running. Once the conversation moves on
     * the same failure reads inside its stretch instead (see [Entry.Failure]); see [TranscriptRows.liftCurrentFailure].
     */
    data class Failure(val footer: RunFooter) : TranscriptRow {
        override val key: String get() = "failure:${footer.id}"
    }

    /**
     * Everything between two messages the reader sees, behind one summary: the agent's thoughts, its tool calls, a
     * coordinator's working notes, the turns Cursor injected (as their event rows, a run of them behind one line),
     * and the footers of the runs it spans. [live] while the run is still writing into it: the summary then reads
     * "Working" and shimmers; a subagent of it still at work keeps it from settling too (see [StretchSummary.of]).
     * A stretch of one step — a tool call, a subagent, a thought, a footer, an event — is drawn as that step, not as
     * a summary of it; a note is never drawn alone, it reads under the summary with the rest.
     */
    data class Stretch(val entries: List<Entry>, val live: Boolean = false) : TranscriptRow {
        // By its first step's own key: an event it opens with keeps it when the next event folds the two into a group.
        override val key: String get() = "stretch:${entries.first().let { first -> (first as? Entry.Events)?.group?.rows?.first()?.key ?: first.key }}"

        /**
         * The one entry a stretch of one step is drawn as, or null for a stretch worth a summary. A failed run with
         * nothing else in its stretch — its footer and its failure line — is its failure line: the footer's duration
         * is the line's to say, there being no summary to carry it.
         */
        val single: Entry? get() {
            val alone = entries.singleOrNull()
                ?: entries.takeIf { it.size == 2 && it[0] is Entry.Footer && it[1] is Entry.Failure && (it[1] as Entry.Failure).footer.id == (it[0] as Entry.Footer).footer.id }?.get(1)
            return alone?.takeUnless { it is Entry.Note || it is Entry.Events }
        }

        val summary: StretchSummary by lazy { StretchSummary.of(this) }

        /** The entries the open stretch lists: the footers are not among them, the summary already carries them. */
        val listed: List<Entry> get() = entries.filterNot { it is Entry.Footer }

        /** The failed runs the stretch holds, as their lines (see [Entry.Failure]). */
        val failures: List<Entry.Failure> get() = entries.filterIsInstance<Entry.Failure>()

        /** The calls of the stretch drawn as a subagent's row, in order (see [Entry.Call.subagent]). */
        val subagents: List<Entry.Call> by lazy { entries.filter { it is Entry.Call && it.subagent != null }.map { it as Entry.Call } }

        /** How many injected turns the stretch holds, a repeated one counted each time it came. */
        val eventCount: Int get() = entries.sumOf { entry ->
            when (entry) {
                is Entry.Event -> entry.row.count
                is Entry.Events -> entry.group.count
                else -> 0
            }
        }
    }

    /** One step of a [Stretch], in the order it happened. */
    sealed interface Entry {
        val key: String

        data class Thought(val block: ThinkingBlock, override val key: String) : Entry

        /**
         * A tool call. [subagent] is the row it is drawn as when it is a subagent's — a task the agent delegated, a
         * Project worker the coordinator created, messaged or stopped (see [SubagentCall]) — else null.
         */
        data class Call(val call: ToolCall, override val key: String, val subagent: SubagentCall? = SubagentCall.of(call)) : Entry

        /** A coordinator's working note: the prose it wrote between tool calls, which is not its message. */
        data class Note(val message: AssistantMessage) : Entry {
            override val key: String get() = message.id
        }

        /**
         * A run's footer. [interrupted] when the run was cancelled by the user's next message (see
         * [TranscriptRows.interruptedFooters]): the summary then reads "Worked 11m 34s · … · interrupted" — the
         * turn ended because the reader wrote again, which is not a failure and gets no warning.
         */
        data class Footer(val footer: RunFooter, val interrupted: Boolean = false) : Entry {
            override val key: String get() = footer.id
        }

        /**
         * A run the server's status says failed, as one line inside its stretch — "Run failed · <the server's
         * reason> · <when>" — listed with the steps when the stretch opens; the summary ends "· failed" for it. The
         * demoted form of [TranscriptRow.Failure]: the conversation moved past the failure (a newer turn, a newer
         * run, the chat running), so it is an event of its turn, not a banner over the chat.
         */
        data class Failure(val footer: RunFooter) : Entry {
            override val key: String get() = "failure:${footer.id}"
        }

        data class Line(val row: SummaryRow) : Entry {
            override val key: String get() = row.id
        }

        /** A turn Cursor injected, as its one-line row (see [TranscriptRow.Event]). */
        data class Event(val row: TranscriptRow.Event) : Entry {
            override val key: String get() = row.key
        }

        /** A run of injected turns behind one line (see [TranscriptRow.Events]). */
        data class Events(val group: TranscriptRow.Events) : Entry {
            override val key: String get() = group.key
        }
    }
}

/**
 * The summary line of a [TranscriptRow.Stretch]: the verb — "Worked 1m 48s" from the run's footer, "Working" while
 * the run still writes, "2 working" while subagents of it still work, else the first count — then the counts of
 * what the stretch holds, largest kinds first ("4 edits · 2 files · 1 thought"), the line counts of its edits set
 * apart.
 */
data class StretchSummary(val action: String, val details: String?, val lineStats: String?, val busy: Boolean) {
    /** The whole line, for tests and for the diagnostics. */
    val text: String get() = listOfNotNull(action, details).joinToString(" \u00B7 ")

    companion object {
        /** How many counts the line carries; a failure is always among them. */
        private const val MAX_COUNTS = 3

        /**
         * [stretch]'s line. [working] are the rows of its subagents still at work, in order (see
         * [SubagentRows.isWorking]): as the desktop's work group reads (Cursor 3.21.18), the stretch does not settle to
         * "Worked" while one works, whether or not the run that started it has ended. The verb counts them — "2
         * Working" while the run still writes, "2 working" once it has stopped — and in a Project's chat
         * ([coordinator]) it is "2 Working" either way, followed by where the newest of them stands ("Editing
         * AgentListOrganizer.kt") instead of the counts. The line shimmers while any works.
         */
        fun of(stretch: TranscriptRow.Stretch, working: List<SubagentLook> = emptyList(), coordinator: Boolean = false): StretchSummary {
            val entries = stretch.entries.filterIsInstance<TranscriptRow.Entry.Call>()
            // A call that failed is counted once, as a failure, not as the edit or read it did not manage.
            val failed = entries.count { it.call.isError }
            val done = entries.filterNot { it.call.isError }
            val calls = done.map { it.call }
            val work = WorkSummary.of(calls)
            val thoughts = stretch.entries.count { it is TranscriptRow.Entry.Thought && it.block.text.isNotBlank() }
            val notes = stretch.entries.count { it is TranscriptRow.Entry.Note && it.message.markdown.isNotBlank() }
            // Every subagent's row, whatever the call carried, and a coordinator's check on its workers.
            val agents = done.count { it.subagent != null || (it.call.kind == ToolKind.Coordinator && it.call.payload is ToolPayload.WorkerAction) }
            // The calls no count above names — a mode switch, a to-do update, a plan — are steps; a question has its card.
            val steps = done.count { entry ->
                val it = entry.call
                entry.subagent == null && (it.kind == ToolKind.Other || it.kind == ToolKind.Todo || it.kind == ToolKind.Plan || (it.kind == ToolKind.Coordinator && it.payload !is ToolPayload.WorkerAction))
            }
            val counts = listOfNotNull(
                plural(stretch.eventCount, "event"),
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
            // A stretch spanning several runs — silent turns one after another — worked for all their time together.
            val footerEntries = stretch.entries.filterIsInstance<TranscriptRow.Entry.Footer>()
            val footers = footerEntries.map { it.footer }
            val last = footerEntries.lastOrNull()
            val footer = last?.footer?.let { f ->
                val durations = footers.mapNotNull { it.durationMs }
                if (durations.isEmpty()) f else f.copy(durationMs = durations.sum())
            }
            val action = when {
                working.isNotEmpty() -> "${working.size} ${if (stretch.live || coordinator) "Working" else "working"}"
                // Still being written: whatever earlier runs closed inside it, the stretch is working.
                stretch.live -> "Working"
                footer != null -> footerLabel(footer, interrupted = last?.interrupted == true)
                else -> counts.firstOrNull() ?: failure ?: "Worked"
            }
            val rest = if (footer != null || stretch.live || working.isNotEmpty()) counts else counts.drop(1)
            // The reader's next message cut the run short: said at the end of the line, quietly, never as a warning.
            val interrupted = INTERRUPTED.takeIf { !stretch.live && footerEntries.any { it.interrupted } }
            // A run the server says failed: its line is inside the stretch (see Entry.Failure), and the summary's last
            // word says it is there — the verb stays what the run did ("Worked 2m 3s"), the failure is not a banner.
            val runFailed = FAILED.takeIf { !stretch.live && stretch.entries.any { it is TranscriptRow.Entry.Failure } }
            val status = working.lastOrNull()?.status?.takeIf { coordinator }
            val details = status ?: (rest.take(MAX_COUNTS) + listOfNotNull(failure.takeIf { action != failure }, interrupted, runFailed)).joinToString(" \u00B7 ").ifEmpty { null }
            return StretchSummary(action, details, work.lineStats, busy = stretch.live || working.isNotEmpty())
        }

        /**
         * The footer's own line: "Worked 1m 48s", "Cancelled after 2m 3s", "Expired". A run the reader's next
         * message cut short ([interrupted]) worked until then: "Worked 11m 34s", the interruption being the summary's
         * last word (see [of]) rather than the verb; so did a run that failed, whose failure is a line of its own with
         * the server's reason (see [TranscriptRow.Failure], [TranscriptRow.Entry.Failure]) — the verb never says
         * "Failed" for it, and the summary ends "· failed" when the line is inside.
         */
        fun footerLabel(footer: RunFooter, interrupted: Boolean = false): String {
            val ending = when (footer.status) {
                RunStatus.CANCELLED -> if (interrupted) null else "Cancelled"
                RunStatus.EXPIRED -> "Expired"
                else -> null
            }
            val duration = TimeFormat.duration(footer.durationMs)
            return when {
                duration != null -> "${ending?.let { "$it after" } ?: "Worked"} $duration"
                else -> ending ?: "Worked"
            }
        }

        /** The summary's word for a run the reader's next message cut short. */
        const val INTERRUPTED = "interrupted"

        /** The summary's word for a run the server says failed, whose line is inside the stretch. */
        const val FAILED = "failed"

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
 * instead, and a subagent's row one of its steps, behind its summary with the rest. A step's pictures and the
 * question a run is paused on follow the stretch they belong to as rows of their own. [runActive] marks the newest
 * stretch as still being written when the run is.
 */
object TranscriptRows {

    fun of(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean = false, interrupted: Set<String> = interruptedFooters(items)): List<TranscriptRow> {
        val rows = cut(items, coordinatorMode, interrupted)
        if (runActive) markLive(rows)
        return openNewestGroup(liftCurrentFailure(rows, runActive))
    }

    /**
     * The rows of [items] before the two words that belong to the transcript as a whole — the newest stretch live,
     * the newest small group of events open (see [of]). A run of items that starts at a user message and ends before
     * the next cuts into the same rows alone as within the whole: nothing here reads past a user message (the stretch
     * closes at it), and [interrupted] carries the one fact that does, the footer the next message cut short. That
     * is what lets [TranscriptPresenter] cut a long transcript a turn at a time.
     */
    internal fun cut(items: List<TimelineItem>, coordinatorMode: Boolean, interrupted: Set<String>): MutableList<TranscriptRow> {
        val rows = ArrayList<TranscriptRow>(items.size)
        val open = ArrayList<TranscriptRow.Entry>()
        // What follows the stretch as rows of its own once it closes: its pictures, then the question it waits on.
        val media = ArrayList<TranscriptRow>()
        val questions = ArrayList<TranscriptRow>()
        // The reason a banner an earlier build wrote for a failed run carried, for the footer that follows it.
        var legacyReason: String? = null

        fun flush() {
            if (open.isNotEmpty()) {
                rows += TranscriptRow.Stretch(foldEvents(open))
                open.clear()
            }
            rows += media
            rows += questions
            media.clear()
            questions.clear()
        }

        for (item in items) {
            if (item !is RunFooter && item !is NoticeCard) legacyReason = null
            when (item) {
                // A cancel is not an error: the notice earlier builds wrote for it is not drawn (the footer says it,
                // quietly). Nor is the banner they wrote for a failure: the footer's line says it, with the reason the
                // banner carried (see RunFooter.reason).
                is NoticeCard -> when {
                    item.isLegacyCancelNotice -> Unit
                    item.isLegacyFailureNotice -> {
                        val reason = item.subtitle?.trim()?.takeIf { it.isNotEmpty() }
                        // Written before its footer by every build that wrote one; a copy that has it after is read too.
                        val failure = open.lastOrNull() as? TranscriptRow.Entry.Failure
                        if (failure != null && failure.footer.reason == null && reason != null) {
                            val given = failure.footer.copy(reason = reason)
                            open[open.lastIndex] = TranscriptRow.Entry.Failure(given)
                            val at = open.indexOfLast { it is TranscriptRow.Entry.Footer && it.footer.id == given.id }
                            if (at >= 0) open[at] = (open[at] as TranscriptRow.Entry.Footer).copy(footer = given)
                        } else {
                            legacyReason = reason
                        }
                    }
                    else -> {
                        flush()
                        rows += TranscriptRow.Item(item)
                    }
                }
                is UserMessage -> {
                    flush()
                    rows += TranscriptRow.Item(item)
                }
                // An injected turn is not a message the reader sent or was sent: it reads inside the stretch, as its line.
                is SystemNotification -> open += TranscriptRow.Entry.Event(TranscriptRow.Event(item))
                is AssistantMessage -> if (coordinatorMode) {
                    open += TranscriptRow.Entry.Note(item)
                } else {
                    flush()
                    rows += TranscriptRow.Item(item)
                }
                // The footer closes its run, not the stretch: a silent turn and the next read as one until a message.
                // A run the server says failed has its line inside the stretch as well, with the reason and the
                // time; the newest such line, with nothing after it, is lifted to a row of its own (see [liftCurrentFailure]).
                is RunFooter -> {
                    val footer = if (item.isFailure && item.reason == null && legacyReason != null) item.copy(reason = legacyReason) else item
                    legacyReason = null
                    open += TranscriptRow.Entry.Footer(footer, interrupted = footer.id in interrupted)
                    if (footer.isFailure) open += TranscriptRow.Entry.Failure(footer)
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
        return rows
    }

    /**
     * The footers of the runs the reader's next message cut short: a run that ended cancelled and is followed at
     * once by a prompt of the user's — in Cursor, a message sent to a chat mid-turn cancels the turn under way and
     * starts the next on the message — within [INTERRUPTION_WINDOW_MS] of the run's end when both times are known
     * (a run stopped and written to again an hour later was stopped, not interrupted). A turn Cursor injected after
     * a cancelled run does not make it one; neither does a run that ended any other way.
     */
    fun interruptedFooters(items: List<TimelineItem>): Set<String> {
        var found: MutableSet<String>? = null
        for (i in items.indices) {
            val footer = items[i] as? RunFooter ?: continue
            if (footer.status != RunStatus.CANCELLED) continue
            // The notice an earlier build wrote for the cancel is not part of the sequence.
            var j = i + 1
            while ((items.getOrNull(j) as? NoticeCard)?.isLegacyCancelNotice == true) j++
            val next = items.getOrNull(j) as? UserMessage ?: continue
            val ended = footer.endedAtMillis
            val sent = next.timestampMillis
            if (ended != null && sent != null && sent - ended > INTERRUPTION_WINDOW_MS) continue
            (found ?: HashSet<String>().also { found = it }) += footer.id
        }
        return found ?: emptySet()
    }

    /** How long after a run's end the next prompt still counts as the message that ended it: the two records are written seconds apart. */
    const val INTERRUPTION_WINDOW_MS = 10 * 60_000L

    /**
     * The failure the conversation has not moved past, as a row of its own: the newest stretch ends on a failed
     * run's line — nothing after it but the run's own pictures or question, no newer turn, and nothing running
     * ([runActive]) — so the line is lifted out to stand after the stretch, where the reader sees it without opening
     * anything ([TranscriptRow.Failure]). Every other failure, and this one once the chat moves on, stays inside its
     * stretch (see [TranscriptRow.Entry.Failure]). A stretch left with the run's footer alone goes: the line says
     * what the footer would ("Run failed after 2m 3s" when the server gave no reason).
     */
    internal fun liftCurrentFailure(rows: List<TranscriptRow>, runActive: Boolean): List<TranscriptRow> {
        if (runActive) return rows
        val (index, stretch) = currentFailureStretch(rows) ?: return rows
        val failure = stretch.entries.last() as TranscriptRow.Entry.Failure
        val rest = stretch.entries.dropLast(1)
        val out = rows.toMutableList()
        if (rest.size == 1 && rest[0] is TranscriptRow.Entry.Footer) {
            out.removeAt(index)
            out.add(index, TranscriptRow.Failure(failure.footer))
        } else {
            out[index] = stretch.copy(entries = rest)
            out.add(index + 1, TranscriptRow.Failure(failure.footer))
        }
        return out
    }

    /** The newest stretch and its index when it ends on a failed run's line with only pictures or a question after it; else null. */
    internal fun currentFailureStretch(rows: List<TranscriptRow>): Pair<Int, TranscriptRow.Stretch>? {
        val last = rows.indexOfLast { it is TranscriptRow.Stretch }
        if (last < 0) return null
        val stretch = rows[last] as TranscriptRow.Stretch
        if (stretch.live || stretch.entries.lastOrNull() !is TranscriptRow.Entry.Failure) return null
        if (rows.subList(last + 1, rows.size).any { it !is TranscriptRow.Media && it !is TranscriptRow.Question }) return null
        return last to stretch
    }

    /**
     * The newest stretch is the one still being written, unless its run has already closed with a footer. An
     * earlier run's footer inside it — a silent turn's, before the injected turn now running — is not the end of it.
     */
    private fun markLive(rows: MutableList<TranscriptRow>) {
        val last = rows.indexOfLast { it is TranscriptRow.Stretch }
        if (last < 0 || !isLiveCandidate(rows, last)) return
        rows[last] = (rows[last] as TranscriptRow.Stretch).copy(live = true)
    }

    /** Whether the stretch at [index] — the newest — is still being written: no footer closes it, and only its pictures or question follow it. */
    internal fun isLiveCandidate(rows: List<TranscriptRow>, index: Int): Boolean {
        val stretch = rows[index] as TranscriptRow.Stretch
        // A footer closes the run; so does the failure line that follows a failed run's footer.
        if (stretch.entries.lastOrNull().let { it is TranscriptRow.Entry.Footer || it is TranscriptRow.Entry.Failure }) return false
        return rows.subList(index + 1, rows.size).none { it !is TranscriptRow.Media && it !is TranscriptRow.Question }
    }

    /**
     * The stretch's entries with its injected turns folded: the same notice arriving several times in a row — a
     * pull request synchronized twice — is one row counted twice, and two or more events in a row (the footers of
     * their runs between them, which the summary carries) are one [TranscriptRow.Events] behind one line. Anything
     * else between two events — a note, a thought, a call — keeps them apart.
     */
    private fun foldEvents(entries: List<TranscriptRow.Entry>): List<TranscriptRow.Entry> {
        if (entries.none { it is TranscriptRow.Entry.Event }) return entries.toList()
        val deduped = ArrayList<TranscriptRow.Entry>(entries.size)
        for (entry in entries) {
            val previous = deduped.lastOrNull { it !is TranscriptRow.Entry.Footer }
            if (entry is TranscriptRow.Entry.Event && previous is TranscriptRow.Entry.Event && sameNotice(previous.row.notification, entry.row.notification)) {
                deduped[deduped.indexOf(previous)] = TranscriptRow.Entry.Event(previous.row.copy(count = previous.row.count + entry.row.count))
            } else {
                deduped += entry
            }
        }
        val out = ArrayList<TranscriptRow.Entry>(deduped.size)
        var i = 0
        while (i < deduped.size) {
            if (deduped[i] !is TranscriptRow.Entry.Event) {
                out += deduped[i]
                i++
                continue
            }
            // The run of events from here, footers between them allowed, up to the first entry of another kind.
            var j = i
            var lastEvent = i
            while (j < deduped.size && (deduped[j] is TranscriptRow.Entry.Event || deduped[j] is TranscriptRow.Entry.Footer)) {
                if (deduped[j] is TranscriptRow.Entry.Event) lastEvent = j
                j++
            }
            val run = deduped.subList(i, lastEvent + 1)
            val events = run.filterIsInstance<TranscriptRow.Entry.Event>()
            if (events.size >= 2) {
                out += TranscriptRow.Entry.Events(TranscriptRow.Events(events.map { it.row }))
                out += run.filterIsInstance<TranscriptRow.Entry.Footer>()
            } else {
                out += run
            }
            i = lastEvent + 1
        }
        return out
    }

    private fun sameNotice(a: SystemNotification, b: SystemNotification): Boolean =
        a.kind == b.kind && a.title == b.title && a.summary == b.summary && a.agentId == b.agentId

    /** The newest group of events in the transcript opens on its own when it holds fewer than [OPEN_BELOW] events. */
    private fun openNewestGroup(rows: List<TranscriptRow>): List<TranscriptRow> {
        val (r, stretch, e) = newestGroupToOpen(rows) ?: return rows
        return rows.toMutableList().also { it[r] = withGroupOpen(stretch, e) }
    }

    /** The newest group of events, when it is small enough to open: its stretch's index, the stretch, and the entry's index within it. */
    internal fun newestGroupToOpen(rows: List<TranscriptRow>): Triple<Int, TranscriptRow.Stretch, Int>? {
        for (r in rows.indices.reversed()) {
            val stretch = rows[r] as? TranscriptRow.Stretch ?: continue
            val e = stretch.entries.indexOfLast { it is TranscriptRow.Entry.Events }
            if (e < 0) continue
            val group = (stretch.entries[e] as TranscriptRow.Entry.Events).group
            return if (group.count >= OPEN_BELOW || group.startsOpen) null else Triple(r, stretch, e)
        }
        return null
    }

    /** [stretch] with the group at [entryIndex] marked as opening on its own. */
    internal fun withGroupOpen(stretch: TranscriptRow.Stretch, entryIndex: Int): TranscriptRow.Stretch {
        val group = (stretch.entries[entryIndex] as TranscriptRow.Entry.Events).group
        val entries = stretch.entries.toMutableList()
        entries[entryIndex] = TranscriptRow.Entry.Events(group.copy(startsOpen = true))
        return stretch.copy(entries = entries)
    }

    /** A newest group with fewer events than this is shown open. */
    const val OPEN_BELOW = 3
}
