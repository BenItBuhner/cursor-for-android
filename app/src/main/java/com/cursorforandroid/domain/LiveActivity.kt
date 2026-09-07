package com.cursorforandroid.domain

import com.cursorforandroid.util.TimeFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Coarse state of a tracked run as shown in the live notification header. */
enum class LivePhase { Starting, Running, Stopping, Finished }

/**
 * What the live notification knows about one run: the Android counterpart of the iOS Live Activity content
 * (status label, title, "+80 −230 · 3 Files", Review).
 */
data class TrackedRun(
    val agentId: String,
    val runId: String,
    val title: String,
    val status: RunStatus,
    val phase: LivePhase,
    val startedAtMillis: Long,
    val digest: RunDigest = RunDigest(),
    val durationMs: Long? = null,
    val branch: String? = null,
    val prUrl: String? = null,
    /** Final assistant reply for finished runs; the most recent assistant text otherwise. */
    val summary: String? = null,
    val finishedAtMillis: Long? = null,
) {
    val isFinished: Boolean get() = phase == LivePhase.Finished

    /** "+80 −230 · 3 Files", "3 Files · Worked 3m 5s", "Worked 3m 5s" or null: the second line of a finished card. */
    fun statsLine(): String? = digest.statsLine(durationMs)
}

/** Aggregate of a run's tool calls plus a description of what the agent is doing right now. */
data class RunDigest(
    val filesEdited: Int = 0,
    val filesRead: Int = 0,
    val searches: Int = 0,
    val commands: Int = 0,
    val subagents: Int = 0,
    val additions: Int? = null,
    val deletions: Int? = null,
    val activity: Activity = Activity.Starting,
) {
    /** Current step, e.g. `Editing Composer.kt`. [detail] is null for steps without a subject. */
    data class Activity(val verb: String, val detail: String? = null) {
        val label: String get() = if (detail.isNullOrBlank()) verb else "$verb $detail"

        companion object {
            val Starting = Activity("Starting")
            val Thinking = Activity("Thinking")
            val Writing = Activity("Writing")
            val Working = Activity("Working")
            val Finishing = Activity("Finishing")
        }
    }

    val hasLineStats: Boolean get() = additions != null || deletions != null

    /** "+80 −230" with a proper minus sign, or null when no tool reported line counts. */
    fun lineStats(): String? = if (!hasLineStats) null else "+${additions ?: 0} \u2212${deletions ?: 0}"

    fun filesLabel(): String? = when (filesEdited) {
        0 -> null
        1 -> "1 File"
        else -> "$filesEdited Files"
    }

    fun statsLine(durationMs: Long?): String? {
        val worked = TimeFormat.duration(durationMs)?.let { "Worked $it" }
        val parts = when {
            hasLineStats -> listOfNotNull(lineStats(), filesLabel())
            else -> listOfNotNull(filesLabel(), worked)
        }
        return parts.joinToString(" \u00B7 ").ifBlank { null }
    }

    companion object {
        private const val DETAIL_MAX = 40

        /** Derives the digest from the same timeline items the conversation renders. */
        fun from(items: List<TimelineItem>): RunDigest {
            val calls = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
            val edits = calls.filter { it.kind == ToolKind.Edit }
            val reads = calls.filter { it.kind == ToolKind.Read || it.kind == ToolKind.List }
            val stats = edits.mapNotNull { DiffStats.fromToolResult(it.result) }
            return RunDigest(
                filesEdited = edits.distinctBy { it.subject() }.size,
                filesRead = reads.distinctBy { it.subject() }.size,
                searches = calls.count { it.kind == ToolKind.Search || it.kind == ToolKind.Web },
                commands = calls.count { it.kind == ToolKind.Shell },
                subagents = items.filterIsInstance<SubagentsCard>().sumOf { it.subagents.size },
                additions = stats.takeIf { it.isNotEmpty() }?.sumOf { it.additions },
                deletions = stats.takeIf { it.isNotEmpty() }?.sumOf { it.deletions },
                activity = activityOf(items),
            )
        }

        private fun ToolCall.subject(): String = if (summary.isBlank()) callId else "$kind:$summary"

        private fun activityOf(items: List<TimelineItem>): Activity = when (val last = items.lastOrNull()) {
            null -> Activity.Starting
            is AssistantMessage -> if (last.isStreaming) Activity.Writing else Activity.Working
            is ActivityGroup, is SubagentsCard -> currentStep(items)
            is RunFooter, is NoticeCard -> Activity.Finishing
            is UserMessage, is SummaryRow -> Activity.Working
        }

        /**
         * The agent's own step — a thought being written, a tool running — comes first; delegation counts once it is
         * waiting on its subagents. Subagent cards sit after the group they were delegated from while that group goes
         * on collecting the agent's work, so both are read from the trailing activity rather than the last item alone.
         */
        private fun currentStep(items: List<TimelineItem>): Activity {
            val trailing = items.takeLastWhile { it is ActivityGroup || it is SubagentsCard }
            val group = trailing.filterIsInstance<ActivityGroup>().lastOrNull()
            if (group?.isThinking == true) return Activity.Thinking
            group?.runningCall?.let { call -> return Activity(ToolNames.verb(call.kind, call.status), call.detail()) }
            val running = trailing.filterIsInstance<SubagentsCard>().sumOf { card -> card.subagents.count { it.status == "Running" } }
            return if (running > 0) Activity("Delegating to", "$running ${if (running == 1) "subagent" else "subagents"}") else Activity.Working
        }

        private fun ToolCall.detail(): String? {
            val raw = summary.trim()
            if (raw.isEmpty()) return null
            val text = when (kind) {
                ToolKind.Read, ToolKind.Edit, ToolKind.List -> raw.trimEnd('/').substringAfterLast('/')
                else -> raw
            }
            return if (text.length > DETAIL_MAX) text.take(DETAIL_MAX - 1).trimEnd() + "\u2026" else text
        }
    }
}

/** Line counts a tool result may carry. The public API does not guarantee them, so every key is optional. */
data class DiffStats(val additions: Int, val deletions: Int) {
    companion object {
        private val ADDED = setOf("additions", "linesadded", "added", "insertions", "lines_added", "added_lines", "addedlines")
        private val REMOVED = setOf("deletions", "linesremoved", "removed", "deleted", "lines_removed", "removed_lines", "removedlines")

        fun fromToolResult(result: JsonElement?): DiffStats? {
            val obj = result as? JsonObject ?: return null
            var added: Int? = null
            var removed: Int? = null
            fun visit(node: JsonObject, depth: Int) {
                for ((key, value) in node) {
                    val normalized = key.lowercase()
                    val number = (value as? JsonPrimitive)?.intOrNull
                    when {
                        number != null && normalized in ADDED -> added = (added ?: 0) + number
                        number != null && normalized in REMOVED -> removed = (removed ?: 0) + number
                        value is JsonObject && depth < 3 -> visit(value, depth + 1)
                    }
                }
            }
            visit(obj, 0)
            if (added == null && removed == null) return null
            return DiffStats(added ?: 0, removed ?: 0)
        }
    }
}

/** Everything the live notification renders. */
data class LiveActivityState(
    /** The runs being followed through their stream: at most the monitor's tracking cap, so a subset when many run. */
    val running: List<TrackedRun> = emptyList(),
    /** False until the monitor has reconciled against the agent list once; guards against an early shutdown. */
    val hasReconciled: Boolean = false,
    /** Running agents in the last reconciled list, including the ones beyond the tracking cap. */
    val runningCount: Int = 0,
) {
    val isIdle: Boolean get() = hasReconciled && running.isEmpty()

    /** How many agents are running: the list's count, but never fewer than the runs actually being followed. */
    val totalRunning: Int get() = maxOf(runningCount, running.size)

    /** Running agents the notification has no line for because they are beyond the tracking cap. */
    val untrackedCount: Int get() = totalRunning - running.size
}
