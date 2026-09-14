package com.cursorforandroid.domain

/**
 * What a chat's own transcript says about its goal, without any account service: the documented stream carries the
 * agent's `CreateGoal` and `UpdateGoal` calls as `tool_call` events (`createGoal` / `updateGoal` in the SDK's
 * vocabulary; their `agent.v1` argument shapes are read into [ToolPayload.GoalChange]), and the documented `/v0`
 * transcript carries every turn Cursor started to keep working toward the goal as a "Goal continued" notification
 * with the objective in it (see [SystemNotifications]). From those, in order, this derives the [Goal] the strip above
 * the composer shows ([derive]), and lifts each goal call out of the stretch of work it sits in into a compact row of
 * its own — "Goal set · Ship the release…", "Goal completed" — with the treatment the injected turns' rows have
 * ([lift]), which is how the desktop keeps a goal apart from the agent's tool work.
 *
 * Time is kept the desktop's way (`GoalState.active_duration_ms` + `last_accrued_at_ms`): active time accrues from
 * the moment the goal is set or resumed and stops when it is paused or completed. The stream's events carry no
 * clock, so the turn's start — the run's `createdAt`, which every prompt and injected turn already carries — stands
 * for the moment of each change; a goal is set in the first moments of its turn in practice (`/goal` is the prompt),
 * so the count is right to within the turn's opening.
 *
 * The same reading is applied to what is already on disk. A trace written by a build that did not read the goal
 * tools kept the call by name with nothing read off its arguments; it is re-read on its way to the screen
 * ([reinterpret]), and a set-goal call whose objective that build dropped is marked as missing its text
 * ([needsRefresh] says the turn is worth asking for again).
 */
object GoalTranscript {

    /** True when [call] is `CreateGoal` or `UpdateGoal`, by name under any spelling or by the payload an earlier build read off it. */
    fun isGoalCall(call: ToolCall): Boolean = call.payload is ToolPayload.GoalChange || ToolNames.goalTool(call.name) != null

    /** True when [items] carry a goal call in any turn shown: the chat has (or had) a goal on it. */
    fun hasGoalContent(items: List<TimelineItem>): Boolean = calls(items).any(::isGoalCall)

    /**
     * [call] as this build reads it. A goal call kept by a build that did not read the tool — the name is a goal
     * tool's, the payload is none — is given one: the objective when that build kept it as the row's detail, else a
     * marker that the text is missing from this copy. Every other call is returned as it is.
     */
    fun reinterpret(call: ToolCall): ToolCall {
        if (call.payload is ToolPayload.GoalChange) return call
        val action = ToolNames.goalTool(call.name) ?: return call
        val payload = when (action) {
            ToolPayload.GoalChange.Action.Set -> {
                val objective = call.detail?.trim()?.takeIf { it.isNotEmpty() }
                ToolPayload.GoalChange(action, objective = objective, missing = objective == null)
            }
            ToolPayload.GoalChange.Action.Update -> ToolPayload.GoalChange(action, status = GoalStatus.parse(call.summary))
        }
        return call.copy(payload = payload)
    }

    /**
     * True when [items] hold a set-goal call whose objective this copy lacks and a replay could bring: one an earlier
     * build dropped on its way to disk. A call the stream itself left the arguments out of (`truncated.args`) is not
     * one — asking again would bring the same frame.
     */
    fun needsRefresh(items: List<TimelineItem>): Boolean = calls(items).any { call ->
        if (call.truncated?.args == true) return@any false
        val payload = reinterpret(call).payload as? ToolPayload.GoalChange ?: return@any false
        payload.action == ToolPayload.GoalChange.Action.Set && payload.missing
    }

    /**
     * The goal the transcript leaves the chat with, or null when it has none to show: no goal was ever set in the
     * turns shown, the goal was cleared, or it was completed and a later turn has begun since. A goal completed in
     * the newest turn is still returned, so the strip can say so where the goal used to be.
     */
    fun derive(items: List<TimelineItem>): Goal? {
        var goal: Goal? = null
        var turnStart: Long? = null
        for (item in items) {
            when (item) {
                is UserMessage -> {
                    turnStart = item.timestampMillis
                    goal = nextTurn(goal)
                }
                is SystemNotification -> {
                    turnStart = item.timestampMillis ?: turnStart
                    goal = if (item.kind == SystemNotification.Kind.Goal && item.title == SystemNotifications.GOAL_CONTINUED) {
                        continued(goal, item, turnStart)
                    } else {
                        nextTurn(goal)
                    }
                }
                is ActivityGroup -> for (call in item.calls) {
                    val change = reinterpret(call).payload as? ToolPayload.GoalChange ?: continue
                    goal = apply(goal, call, change, turnStart)
                }
                else -> Unit
            }
        }
        return goal
    }

    /**
     * [goal] as a new turn finds it: a goal that ended has had its turn on the strip and goes; an open goal stays,
     * the "updated" it led with settling back to the status once the turn that changed it is over.
     */
    private fun nextTurn(goal: Goal?): Goal? = when {
        goal == null -> null
        !goal.status.isOpen -> null
        goal.lastChange == Goal.Change.Updated -> goal.copy(lastChange = Goal.Change.Set)
        else -> goal
    }

    /**
     * A turn Cursor started to keep working toward the goal: the goal is active, whatever the transcript said before,
     * and the objective is the one Cursor wrote into the turn — which is where an objective edited on another client
     * shows up. A continuation for a goal the turns shown never saw set is the first word of it.
     */
    private fun continued(goal: Goal?, item: SystemNotification, turnStart: Long?): Goal? {
        val objective = item.body?.trim()?.takeIf { it.isNotEmpty() } ?: return goal
        if (goal == null || !goal.status.isOpen) {
            return Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = turnStart, continuationCount = 1, lastChange = Goal.Change.Continued)
        }
        val reworded = objective != goal.objective
        return goal.copy(
            objective = objective,
            status = GoalStatus.ACTIVE,
            accruingSinceMillis = goal.accruingSinceMillis ?: turnStart,
            continuationCount = goal.continuationCount + 1,
            lastChange = if (reworded) Goal.Change.Updated else Goal.Change.Continued,
        )
    }

    /** [goal] after the agent's goal call [call]: set, or moved to the status the update asked for. A refused call changes nothing. */
    private fun apply(goal: Goal?, call: ToolCall, change: ToolPayload.GoalChange, turnStart: Long?): Goal? {
        if (call.isError || change.error != null) return goal
        return when (change.action) {
            ToolPayload.GoalChange.Action.Set -> {
                val objective = change.objective ?: goal?.objective ?: return goal
                if (goal != null && goal.status.isOpen) {
                    // A goal set over an open one is the objective changing hands; the time already worked stands.
                    goal.copy(objective = objective, status = GoalStatus.ACTIVE, accruingSinceMillis = goal.accruingSinceMillis ?: turnStart, lastChange = Goal.Change.Updated)
                } else {
                    Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = turnStart, lastChange = Goal.Change.Set)
                }
            }
            ToolPayload.GoalChange.Action.Update -> {
                val status = change.status ?: return goal
                val current = goal ?: return null
                when (status) {
                    GoalStatus.ACTIVE -> current.copy(status = GoalStatus.ACTIVE, accruingSinceMillis = current.accruingSinceMillis ?: turnStart, lastChange = Goal.Change.Resumed)
                    GoalStatus.PAUSED -> current.copy(status = GoalStatus.PAUSED, activeDurationMs = accrued(current, turnStart), accruingSinceMillis = null, lastChange = Goal.Change.Paused)
                    GoalStatus.COMPLETE -> current.copy(status = GoalStatus.COMPLETE, activeDurationMs = accrued(current, turnStart), accruingSinceMillis = null, lastChange = Goal.Change.Completed)
                    GoalStatus.CLEARED -> null
                }
            }
        }
    }

    /** The active time up to [at]: what was accrued, plus the stretch under way when both ends are known. */
    private fun accrued(goal: Goal, at: Long?): Long {
        val since = goal.accruingSinceMillis ?: return goal.activeDurationMs
        if (at == null) return goal.activeDurationMs
        return goal.activeDurationMs + (at - since).coerceAtLeast(0L)
    }

    /**
     * [items] with every goal call lifted out of its stretch of work into a row of its own, in place: the steps
     * before it stay one group, the steps after it start another, and the call itself becomes a [SystemNotification]
     * of the goal kind — the row an injected goal turn already has — so a goal being set, paused, resumed or
     * completed reads as the event it is rather than as a step behind an "Explored" header. Every call on the way
     * is [reinterpret]ed. Items without a goal call are returned as they are, the same instance.
     */
    fun lift(items: List<TimelineItem>): List<TimelineItem> {
        if (items.none { it is ActivityGroup && it.calls.any(::isGoalCall) }) return items
        val out = ArrayList<TimelineItem>(items.size + 4)
        var turnStart: Long? = null
        for (item in items) {
            when (item) {
                is UserMessage -> turnStart = item.timestampMillis
                is SystemNotification -> turnStart = item.timestampMillis ?: turnStart
                else -> Unit
            }
            if (item !is ActivityGroup || item.calls.none(::isGoalCall)) {
                out += item
                continue
            }
            var groups = 0
            var steps = ArrayList<ActivityStep>()
            fun flush() {
                if (steps.isEmpty()) return
                out += ActivityGroup(if (groups == 0) item.id else "${item.id}/$groups", steps)
                groups++
                steps = ArrayList()
            }
            for (step in item.steps) {
                if (step is ToolCall && isGoalCall(step)) {
                    flush()
                    out += row(item.id, reinterpret(step), turnStart)
                } else {
                    steps += step
                }
            }
            flush()
        }
        return out
    }

    /**
     * The row for one goal call: the event as its title — "Goal set", "Goal completed", "Goal paused", "Goal not
     * set" — the objective's first line beside it and the whole of it behind it for a goal being set, the refusal's
     * words for a call the account refused. Press and hold copies the objective.
     */
    fun row(groupId: String, call: ToolCall, timestampMillis: Long?): SystemNotification {
        val change = call.payload as? ToolPayload.GoalChange ?: ToolPayload.GoalChange(ToolNames.goalTool(call.name) ?: ToolPayload.GoalChange.Action.Set, missing = true)
        val error = change.error?.trim()?.takeIf { it.isNotEmpty() } ?: GOAL_FAILED.takeIf { call.isError }
        val objective = change.objective?.trim()?.takeIf { it.isNotEmpty() }
        val title: String
        val tone: NoticeTone
        when {
            error != null -> {
                title = if (change.action == ToolPayload.GoalChange.Action.Set) "Goal not set" else "Goal not updated"
                tone = NoticeTone.Error
            }
            change.action == ToolPayload.GoalChange.Action.Set -> {
                title = if (call.isRunning) "Setting goal" else "Goal set"
                tone = NoticeTone.Neutral
            }
            else -> when (change.status) {
                GoalStatus.COMPLETE -> { title = if (call.isRunning) "Completing goal" else "Goal completed"; tone = NoticeTone.Success }
                GoalStatus.PAUSED -> { title = if (call.isRunning) "Pausing goal" else "Goal paused"; tone = NoticeTone.Neutral }
                GoalStatus.ACTIVE -> { title = if (call.isRunning) "Resuming goal" else "Goal resumed"; tone = NoticeTone.Neutral }
                GoalStatus.CLEARED -> { title = if (call.isRunning) "Clearing goal" else "Goal cleared"; tone = NoticeTone.Neutral }
                null -> { title = if (call.isRunning) "Updating goal" else "Goal updated"; tone = NoticeTone.Neutral }
            }
        }
        val summary = when {
            error != null -> error
            change.action == ToolPayload.GoalChange.Action.Set -> objective?.let(::firstLine) ?: if (change.missing) OBJECTIVE_MISSING else null
            else -> null
        }
        val body = when {
            error != null -> objective
            change.action == ToolPayload.GoalChange.Action.Set -> objective
            else -> null
        }
        return SystemNotification(
            id = "$groupId/goal-${call.callId}",
            kind = SystemNotification.Kind.Goal,
            title = title,
            summary = summary,
            body = body,
            tone = tone,
            raw = objective ?: listOfNotNull(title, error).joinToString(": "),
            timestampMillis = timestampMillis,
        )
    }

    private fun firstLine(text: String): String = (text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()).replace(WHITESPACE, " ")

    private fun calls(items: List<TimelineItem>): Sequence<ToolCall> = items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }

    private val WHITESPACE = Regex("""\s+""")

    /** The desktop's words for a goal result without an error message of its own. */
    const val GOAL_FAILED = "Goal operation failed"

    /** What the row says in place of an objective this copy of the turn does not have. */
    const val OBJECTIVE_MISSING = "The objective isn't in this copy of the turn."
}
