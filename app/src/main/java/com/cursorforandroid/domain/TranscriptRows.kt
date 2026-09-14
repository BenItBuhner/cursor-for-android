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

    /** An item drawn as itself: a prompt, an injected turn's row, an agent's reply, a notice. */
    data class Item(val item: TimelineItem) : TranscriptRow {
        override val key: String get() = item.id
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
                is UserMessage, is SystemNotification, is NoticeCard -> {
                    flush()
                    rows += TranscriptRow.Item(item)
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
        if (!runActive) return rows
        // The newest stretch is the one still being written, unless its run has already closed with a footer.
        val last = rows.indexOfLast { it is TranscriptRow.Stretch }
        if (last < 0) return rows
        val stretch = rows[last] as TranscriptRow.Stretch
        if (stretch.entries.any { it is TranscriptRow.Entry.Footer }) return rows
        if (rows.subList(last + 1, rows.size).any { it !is TranscriptRow.Media && it !is TranscriptRow.Question }) return rows
        rows[last] = stretch.copy(live = true)
        return rows
    }
}
