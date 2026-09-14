package com.cursorforandroid.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `agent.v1.GoalStatus`: where a chat's goal stands. A goal is the objective `/goal` sets in the composer, which the
 * agent files with its `CreateGoal` tool and moves along with `UpdateGoal`, and which Cursor keeps pursuing across
 * turns until it is complete or cleared. Spelled `GOAL_STATUS_*` on the wire; ACTIVE 1, PAUSED 2, COMPLETE 3,
 * CLEARED 4.
 */
@Serializable
enum class GoalStatus(val number: Int) {
    ACTIVE(1),
    PAUSED(2),
    COMPLETE(3),
    CLEARED(4),
    ;

    val wireName: String get() = "$WIRE_PREFIX$name"

    /** True while the goal is still the chat's: being worked toward, or held until it is resumed. */
    val isOpen: Boolean get() = this == ACTIVE || this == PAUSED

    companion object {
        const val WIRE_PREFIX = "GOAL_STATUS_"

        /**
         * The proto name (`GOAL_STATUS_COMPLETE`), the bare name in any case, the number, or the word a hand-written
         * stream might use (`completed`, `done`, `resumed`); null for anything else, including UNSPECIFIED.
         */
        fun parse(raw: String?): GoalStatus? {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } }
            return when (token.uppercase().removePrefix(WIRE_PREFIX)) {
                "ACTIVE", "RESUMED", "RESUME", "IN_PROGRESS" -> ACTIVE
                "PAUSED", "PAUSE" -> PAUSED
                "COMPLETE", "COMPLETED", "DONE", "FINISHED", "ACHIEVED" -> COMPLETE
                "CLEARED", "CLEAR", "CANCELLED", "CANCELED" -> CLEARED
                else -> null
            }
        }

        /** [parse] of a JSON value: the enum's name as a string, or its number. */
        fun parse(element: JsonElement?): GoalStatus? {
            val primitive = element as? JsonPrimitive ?: return null
            primitive.intOrNull?.let { number -> return entries.firstOrNull { it.number == number } }
            return parse(primitive.contentOrNull)
        }
    }
}

/**
 * The goal on a chat, as the strip above the composer shows it: the objective verbatim, its status, and the time the
 * goal has been active — kept the way Cursor's own client keeps it (`agent.v1.GoalState`): [activeDurationMs] accrued
 * up to the moment accrual last stopped, plus everything since [accruingSinceMillis] while the goal is active. Both
 * sources of the app read into the same shape: the account's `GoalState` (Extended mode) carries the fields by name,
 * and the chat's own transcript yields them from the goal's tool calls and continuations and their turns' timestamps
 * (see [GoalTranscript]).
 */
data class Goal(
    val objective: String,
    val status: GoalStatus,
    /** Active time accrued before [accruingSinceMillis] (`active_duration_ms`). */
    val activeDurationMs: Long = 0L,
    /** From when active time keeps accruing (`last_accrued_at_ms`); null while paused or over, and when no source said when. */
    val accruingSinceMillis: Long? = null,
    /** Turns Cursor started by itself to keep working toward the goal (`continuation_count`; the "Goal continued" turns). */
    val continuationCount: Int = 0,
    val goalId: String? = null,
    val source: Source = Source.Transcript,
    /** The newest thing that happened to the goal, which the strip's label leads with. */
    val lastChange: Change = Change.Set,
) {
    /** Where the goal was read from: the documented transcript, or the account's own record of it. */
    enum class Source { Transcript, Account }

    /** What the last goal event was. [Updated] is a new objective on an open goal; [Continued] a turn Cursor started for it. */
    enum class Change { Set, Updated, Continued, Paused, Resumed, Completed, Cleared }

    /** Active time as of [nowMillis], the accrued part plus the stretch under way: what the strip counts up. */
    fun elapsedMillis(nowMillis: Long): Long = activeDurationMs + (accruingSinceMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L)

    /** True while the count changes every second: the goal is active and a source said from when. */
    val isTicking: Boolean get() = status == GoalStatus.ACTIVE && accruingSinceMillis != null

    /** True when a source gave any account of the time: an elapsed figure is worth showing. */
    val hasElapsed: Boolean get() = activeDurationMs > 0L || accruingSinceMillis != null

    /** The strip's label: the status as Cursor's client words it, or the newest change when it is worth leading with. */
    val label: String
        get() = when (status) {
            GoalStatus.ACTIVE -> if (lastChange == Change.Updated) LABEL_UPDATED else LABEL_ACTIVE
            GoalStatus.PAUSED -> LABEL_PAUSED
            GoalStatus.COMPLETE -> LABEL_COMPLETED
            GoalStatus.CLEARED -> LABEL_CLEARED
        }

    companion object {
        /** Cursor's own wording (`glass.goalTray.header` / `headerPaused`) and its extensions here. */
        const val LABEL_ACTIVE = "Goal active"
        const val LABEL_PAUSED = "Goal paused"
        const val LABEL_UPDATED = "Goal updated"
        const val LABEL_COMPLETED = "Goal completed"
        const val LABEL_CLEARED = "Goal cleared"

        /**
         * A goal as the account's `GoalState` reports it (`GetLatestAgentConversationState`, the `goal_state` of the
         * conversation's `ConversationStateStructure`): [activeDurationMs] and [lastAccruedAtMs] are its own fields,
         * and a `last_accrued_at_ms` only counts while the goal is active, as on the desktop.
         */
        fun fromAccount(
            objective: String,
            status: GoalStatus,
            activeDurationMs: Long?,
            lastAccruedAtMs: Long?,
            continuationCount: Int,
            goalId: String?,
        ): Goal = Goal(
            objective = objective,
            status = status,
            activeDurationMs = activeDurationMs ?: 0L,
            accruingSinceMillis = lastAccruedAtMs?.takeIf { status == GoalStatus.ACTIVE },
            continuationCount = continuationCount,
            goalId = goalId?.takeIf { it.isNotBlank() },
            source = Source.Account,
            lastChange = when (status) {
                GoalStatus.ACTIVE -> Change.Set
                GoalStatus.PAUSED -> Change.Paused
                GoalStatus.COMPLETE -> Change.Completed
                GoalStatus.CLEARED -> Change.Cleared
            },
        )
    }
}
