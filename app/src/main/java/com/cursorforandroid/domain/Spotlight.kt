package com.cursorforandroid.domain

/**
 * The one chat or Project the user asked to follow in a notification of its own. A Project's Spotlight follows its
 * coordinator and the chats under it; a chat's follows its run until it ends.
 */
data class SpotlightTarget(val agentId: String, val isProject: Boolean, val startedAtMillis: Long)

/**
 * What the Spotlight notification says right now: a headline step (the collapsed card's text), the work going on
 * under it — the run's subagents, or a Project's running chats — and one tally line.
 */
data class SpotlightView(
    val agentId: String,
    val title: String,
    val step: String,
    val lines: List<String> = emptyList(),
    val tally: String? = null,
    val startedAtMillis: Long,
    val isProject: Boolean = false,
) {
    /** The expanded body: the step, the lines under it, the tally; null when the step is all there is. */
    val body: String? get() = if (lines.isEmpty() && tally == null) null else (listOf(step) + lines + listOfNotNull(tally)).joinToString("\n")
}

/** Builds [SpotlightView]s from the timeline items the conversation renders, so the notification and the transcript agree. */
object SpotlightContent {
    /** Detail lines under the step; past this, one "+N more" line. */
    const val MAX_LINES = 3
    private const val NAME_MAX = 28
    private const val SUBAGENT_MAX = 48

    const val RECONNECTING = "Reconnecting\u2026"
    const val WAITING = "Waiting for the next turn\u2026"

    /** One chat's run: its current step, the subagents it has running, and what it has done so far. */
    fun ofRun(agentId: String, title: String, items: List<TimelineItem>, startedAtMillis: Long, reconnecting: Boolean = false): SpotlightView {
        val digest = RunDigest.from(items)
        val calls = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
        val subagents = calls.filter { it.isRunning && it.kind == ToolKind.Task }.map { ellipsize(it.summary.ifBlank { "Subagent" }, SUBAGENT_MAX) }
        return SpotlightView(
            agentId = agentId,
            title = title,
            step = if (reconnecting) RECONNECTING else step(digest.activity),
            lines = bullets(subagents),
            tally = tally(calls.size, digest),
            startedAtMillis = startedAtMillis,
        )
    }

    /** A chat inside a Project that is running: its name, and its run's items once its stream has said anything. */
    data class Member(val agentId: String, val name: String, val items: List<TimelineItem>?, val startedAtMillis: Long?)

    /**
     * A Project: the coordinator's step while it runs, else how many of its chats run; a line per running chat with
     * the step it is on; and how many of the Project's chats are running.
     */
    fun ofProject(
        projectId: String,
        title: String,
        coordinator: Member?,
        running: List<Member>,
        runningCount: Int,
        chatCount: Int,
        fallbackStartedAtMillis: Long,
    ): SpotlightView {
        val headline = when {
            coordinator != null -> step(RunDigest.from(coordinator.items.orEmpty()).activity)
            runningCount > 0 -> if (runningCount == 1) "1 chat running" else "$runningCount chats running"
            else -> WAITING
        }
        val lines = running.map { member ->
            val activity = member.items?.takeIf { it.isNotEmpty() }?.let { RunDigest.from(it).activity } ?: RunDigest.Activity.Starting
            "${ellipsize(member.name, NAME_MAX)} \u00B7 ${step(activity)}"
        }
        val more = (runningCount - running.size).coerceAtLeast(0)
        val started = (listOfNotNull(coordinator?.startedAtMillis) + running.mapNotNull { it.startedAtMillis }).minOrNull() ?: fallbackStartedAtMillis
        return SpotlightView(
            agentId = projectId,
            title = title,
            step = headline,
            lines = bullets(lines, extra = more),
            // With the coordinator idle the headline already counts them.
            tally = if (coordinator != null && chatCount > 0) "$runningCount of $chatCount chats running" else null,
            startedAtMillis = started,
            isProject = true,
        )
    }

    /** "Editing Composer.kt", or "Thinking…" for a step without a subject. */
    fun step(activity: RunDigest.Activity): String = if (activity.detail.isNullOrBlank()) activity.verb + "\u2026" else activity.label

    private fun bullets(items: List<String>, extra: Int = 0): List<String> {
        val shown = items.take(MAX_LINES)
        val more = items.size - shown.size + extra
        return shown.map { "\u2022 $it" } + listOfNotNull(if (more > 0) "\u2022 +$more more" else null)
    }

    /** "14 tool calls · +80 −12 · 3 Files", or null before the first tool call. */
    private fun tally(calls: Int, digest: RunDigest): String? {
        if (calls == 0) return null
        val count = if (calls == 1) "1 tool call" else "$calls tool calls"
        return listOfNotNull(count, digest.lineStats(), digest.filesLabel()).joinToString(" \u00B7 ")
    }

    private fun ellipsize(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "\u2026"
}
