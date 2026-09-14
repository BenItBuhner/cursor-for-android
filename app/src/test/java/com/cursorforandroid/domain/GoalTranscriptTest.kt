package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The goal a transcript leaves a chat with, from its goal calls and continuations in order, timed the desktop's way:
 * active time accrues from the turn that set or resumed the goal and stops at the turn that paused or completed it.
 */
class GoalTranscriptTest {

    private val objective = "Ship the 0.3.10 release with the goal strip in it."
    private val t0 = 1_789_380_000_000L
    private val minute = 60_000L

    private fun prompt(id: String, at: Long, text: String = "Do it") = UserMessage(id, text, timestampMillis = at)

    private fun call(id: String, name: String, payload: ToolPayload?, status: String = ToolCall.STATUS_COMPLETED, isError: Boolean = false, detail: String? = null, summary: String = "") =
        ToolCall(callId = id, name = name, kind = ToolKind.Other, status = status, summary = summary, detail = detail, isError = isError, payload = payload)

    private fun set(id: String, text: String = objective) = call(id, "createGoal", ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = text))
    private fun update(id: String, status: GoalStatus) = call(id, "updateGoal", ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Update, status = status))
    private fun work(id: String, vararg steps: ActivityStep) = ActivityGroup(id, steps.toList())
    private fun read(id: String) = ToolCall(id, "read", ToolKind.Read, ToolCall.STATUS_COMPLETED, "Main.kt")
    private fun footer(id: String, at: Long) = RunFooter("f-$id", id, RunStatus.FINISHED, 5 * minute, emptyList())

    private fun continued(id: String, at: Long, text: String = objective) = SystemNotifications.parse(
        id,
        "<system_notification source=\"goal\">\nContinue working toward the active thread goal.\n\n<objective>\n$text\n</objective>\n</system_notification>",
        at,
    )!!.notifications.single()

    @Test
    fun `no goal call, no goal`() {
        assertThat(GoalTranscript.derive(emptyList())).isNull()
        assertThat(GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", read("r1")), footer("run1", t0 + minute)))).isNull()
        assertThat(GoalTranscript.hasGoalContent(listOf(work("g1", read("r1"))))).isFalse()
    }

    @Test
    fun `a goal set is active from its turn's start, and the label is Cursor's`() {
        val items = listOf(prompt("m1", t0, "/goal $objective"), work("g1", set("c1"), read("r1")), footer("run1", t0 + minute))
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal.objective).isEqualTo(objective)
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
        assertThat(goal.accruingSinceMillis).isEqualTo(t0)
        assertThat(goal.activeDurationMs).isEqualTo(0L)
        assertThat(goal.lastChange).isEqualTo(Goal.Change.Set)
        assertThat(goal.source).isEqualTo(Goal.Source.Transcript)
        assertThat(goal.label).isEqualTo(Goal.LABEL_ACTIVE)
        assertThat(goal.isTicking).isTrue()
        assertThat(goal.hasElapsed).isTrue()
        assertThat(goal.elapsedMillis(t0 + 90 * minute)).isEqualTo(90 * minute)
        assertThat(GoalTranscript.hasGoalContent(items)).isTrue()
    }

    @Test
    fun `a turn without a timestamp sets a goal with no count`() {
        val goal = GoalTranscript.derive(listOf(UserMessage("m1", "go"), work("g1", set("c1"))))!!
        assertThat(goal.accruingSinceMillis).isNull()
        assertThat(goal.isTicking).isFalse()
        assertThat(goal.hasElapsed).isFalse()
        assertThat(goal.elapsedMillis(t0)).isEqualTo(0L)
    }

    @Test
    fun `a continuation counts, keeps the goal active, and carries a reworded objective`() {
        val items = listOf(
            prompt("m1", t0), work("g1", set("c1")), footer("run1", t0 + 5 * minute),
            continued("m2", t0 + 30 * minute), work("g2", read("r2")), footer("run2", t0 + 40 * minute),
            continued("m3", t0 + 60 * minute, "$objective Then tag it."), work("g3", read("r3")),
        )
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
        assertThat(goal.continuationCount).isEqualTo(2)
        assertThat(goal.objective).isEqualTo("$objective Then tag it.")
        assertThat(goal.lastChange).isEqualTo(Goal.Change.Updated)
        assertThat(goal.label).isEqualTo(Goal.LABEL_UPDATED)
        // The count runs from the turn that set the goal, not from the continuation.
        assertThat(goal.accruingSinceMillis).isEqualTo(t0)
    }

    @Test
    fun `a continuation alone is the goal's first word when the setting turn is out of the window`() {
        val goal = GoalTranscript.derive(listOf(continued("m2", t0), work("g2", read("r2"))))!!
        assertThat(goal.objective).isEqualTo(objective)
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
        assertThat(goal.continuationCount).isEqualTo(1)
        assertThat(goal.accruingSinceMillis).isEqualTo(t0)
        assertThat(goal.lastChange).isEqualTo(Goal.Change.Continued)
    }

    @Test
    fun `pause stops the count at its turn, resume starts it again, the time in between not counted`() {
        val paused = GoalTranscript.derive(
            listOf(
                prompt("m1", t0), work("g1", set("c1")),
                prompt("m2", t0 + 20 * minute, "pause that"), work("g2", update("c2", GoalStatus.PAUSED)),
            ),
        )!!
        assertThat(paused.status).isEqualTo(GoalStatus.PAUSED)
        assertThat(paused.activeDurationMs).isEqualTo(20 * minute)
        assertThat(paused.accruingSinceMillis).isNull()
        assertThat(paused.isTicking).isFalse()
        assertThat(paused.hasElapsed).isTrue()
        assertThat(paused.elapsedMillis(t0 + 500 * minute)).isEqualTo(20 * minute)
        assertThat(paused.label).isEqualTo(Goal.LABEL_PAUSED)
        assertThat(paused.lastChange).isEqualTo(Goal.Change.Paused)

        val resumed = GoalTranscript.derive(
            listOf(
                prompt("m1", t0), work("g1", set("c1")),
                prompt("m2", t0 + 20 * minute), work("g2", update("c2", GoalStatus.PAUSED)),
                prompt("m3", t0 + 100 * minute, "carry on"), work("g3", update("c3", GoalStatus.ACTIVE)),
            ),
        )!!
        assertThat(resumed.status).isEqualTo(GoalStatus.ACTIVE)
        assertThat(resumed.activeDurationMs).isEqualTo(20 * minute)
        assertThat(resumed.accruingSinceMillis).isEqualTo(t0 + 100 * minute)
        assertThat(resumed.elapsedMillis(t0 + 110 * minute)).isEqualTo(30 * minute)
        assertThat(resumed.lastChange).isEqualTo(Goal.Change.Resumed)
        assertThat(resumed.label).isEqualTo(Goal.LABEL_ACTIVE)
    }

    @Test
    fun `completion keeps the goal on show for its turn, then a new prompt takes it down`() {
        val completing = listOf(
            prompt("m1", t0), work("g1", set("c1")), footer("run1", t0 + minute),
            continued("m2", t0 + 45 * minute), work("g2", read("r2"), update("c2", GoalStatus.COMPLETE)), footer("run2", t0 + 50 * minute),
        )
        val complete = GoalTranscript.derive(completing)!!
        assertThat(complete.status).isEqualTo(GoalStatus.COMPLETE)
        assertThat(complete.activeDurationMs).isEqualTo(45 * minute)
        assertThat(complete.accruingSinceMillis).isNull()
        assertThat(complete.label).isEqualTo(Goal.LABEL_COMPLETED)
        assertThat(complete.lastChange).isEqualTo(Goal.Change.Completed)
        assertThat(complete.continuationCount).isEqualTo(1)

        assertThat(GoalTranscript.derive(completing + prompt("m3", t0 + 60 * minute, "thanks"))).isNull()
        // A new goal after the old one is a goal set, its count its own.
        val again = GoalTranscript.derive(completing + prompt("m3", t0 + 60 * minute, "/goal Next") + work("g3", set("c3", "Next")))!!
        assertThat(again.objective).isEqualTo("Next")
        assertThat(again.lastChange).isEqualTo(Goal.Change.Set)
        assertThat(again.activeDurationMs).isEqualTo(0L)
        assertThat(again.accruingSinceMillis).isEqualTo(t0 + 60 * minute)
    }

    @Test
    fun `cleared is no goal at all, and an update with no goal to move changes nothing`() {
        assertThat(GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", set("c1"), update("c2", GoalStatus.CLEARED))))).isNull()
        assertThat(GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", update("c2", GoalStatus.COMPLETE))))).isNull()
    }

    @Test
    fun `a goal set over an open one is the objective changing, the time worked standing`() {
        val goal = GoalTranscript.derive(
            listOf(
                prompt("m1", t0), work("g1", set("c1")),
                prompt("m2", t0 + 10 * minute, "/goal Something else"), work("g2", set("c2", "Something else")),
            ),
        )!!
        assertThat(goal.objective).isEqualTo("Something else")
        assertThat(goal.lastChange).isEqualTo(Goal.Change.Updated)
        assertThat(goal.accruingSinceMillis).isEqualTo(t0)
        assertThat(goal.label).isEqualTo(Goal.LABEL_UPDATED)
    }

    @Test
    fun `a refused call and a call still running leave the goal as it was`() {
        val refused = call("c2", "updateGoal", ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Update, status = GoalStatus.COMPLETE, error = "No active goal"), isError = true)
        val goal = GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", set("c1"), refused)))!!
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
        // A goal being set right now already shows: the objective is in the arguments from the first frame.
        val running = call("c3", "createGoal", ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = "Now"), status = ToolCall.STATUS_RUNNING)
        assertThat(GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", running)))!!.objective).isEqualTo("Now")
    }

    @Test
    fun `a trace from a build that did not read the tool is re-read, and asked for again when the objective is gone`() {
        val bare = call("c1", "createGoal", payload = null)
        val read = GoalTranscript.reinterpret(bare).payload as ToolPayload.GoalChange
        assertThat(read.action).isEqualTo(ToolPayload.GoalChange.Action.Set)
        assertThat(read.missing).isTrue()
        assertThat(GoalTranscript.needsRefresh(listOf(work("g1", bare)))).isTrue()
        // One that kept the objective as the row's detail needs nothing more.
        val kept = call("c1", "create_goal", payload = null, detail = objective)
        assertThat((GoalTranscript.reinterpret(kept).payload as ToolPayload.GoalChange).objective).isEqualTo(objective)
        assertThat(GoalTranscript.needsRefresh(listOf(work("g1", kept)))).isFalse()
        // An update kept without a payload reads its status off the summary this build writes.
        val update = call("c2", "updateGoal", payload = null, summary = "complete")
        assertThat((GoalTranscript.reinterpret(update).payload as ToolPayload.GoalChange).status).isEqualTo(GoalStatus.COMPLETE)
        assertThat(GoalTranscript.needsRefresh(listOf(work("g1", update)))).isFalse()
        // Calls of other tools are untouched.
        val other = read("r1")
        assertThat(GoalTranscript.reinterpret(other)).isSameInstanceAs(other)
        assertThat(GoalTranscript.needsRefresh(listOf(work("g1", other)))).isFalse()
        // And the derivation reads the same trace through the same eyes.
        assertThat(GoalTranscript.derive(listOf(prompt("m1", t0), work("g1", kept), prompt("m2", t0 + minute), work("g2", update)))!!.status).isEqualTo(GoalStatus.COMPLETE)
    }

    @Test
    fun `the lifted rows read as the events they are`() {
        val items = listOf(
            prompt("m1", t0), work("g1", set("c1"), read("r1")), footer("run1", t0 + minute),
            prompt("m2", t0 + 20 * minute), work("g2", read("r2"), update("c2", GoalStatus.PAUSED), read("r3")),
            prompt("m3", t0 + 30 * minute), work("g3", update("c3", GoalStatus.ACTIVE)),
            prompt("m4", t0 + 40 * minute), work("g4", update("c4", GoalStatus.COMPLETE)),
            prompt("m5", t0 + 50 * minute), work("g5", update("c5", GoalStatus.CLEARED), update("c6", GoalStatus.CLEARED).copy(status = ToolCall.STATUS_RUNNING)),
        )
        val rows = GoalTranscript.lift(items).filterIsInstance<SystemNotification>()
        assertThat(rows.map { it.title }).containsExactly("Goal set", "Goal paused", "Goal resumed", "Goal completed", "Goal cleared", "Clearing goal").inOrder()
        assertThat(rows.map { it.tone }).containsExactly(NoticeTone.Neutral, NoticeTone.Neutral, NoticeTone.Neutral, NoticeTone.Success, NoticeTone.Neutral, NoticeTone.Neutral).inOrder()
        assertThat(rows.map { it.timestampMillis }).containsExactly(t0, t0 + 20 * minute, t0 + 30 * minute, t0 + 40 * minute, t0 + 50 * minute, t0 + 50 * minute).inOrder()
        assertThat(rows.map { it.id }.toSet()).hasSize(6)
        assertThat(rows[0].summary).isEqualTo(objective)
        assertThat(rows[1].summary).isNull()
        assertThat(rows[1].raw).isEqualTo("Goal paused")
        // The group around the pause is cut in two, each half keeping its reads; the group ids stay distinct.
        val lifted = GoalTranscript.lift(items)
        val groups = lifted.filterIsInstance<ActivityGroup>()
        assertThat(groups.map { it.id }.toSet()).hasSize(groups.size)
        assertThat(groups.first { it.id == "g2" }.calls.map { it.callId }).containsExactly("r2")
        assertThat(groups.first { it.id == "g2/1" }.calls.map { it.callId }).containsExactly("r3")
        // The prompts and footers keep their places around them.
        assertThat(lifted.filterIsInstance<UserMessage>()).hasSize(5)
        assertThat(lifted.filterIsInstance<RunFooter>()).hasSize(1)
    }

    @Test
    fun `the account's goal state reads into the same shape, its count anchored only while active`() {
        val active = Goal.fromAccount(objective, GoalStatus.ACTIVE, activeDurationMs = 4_512_000L, lastAccruedAtMs = t0, continuationCount = 3, goalId = "8a1c")
        assertThat(active.source).isEqualTo(Goal.Source.Account)
        assertThat(active.isTicking).isTrue()
        assertThat(active.elapsedMillis(t0 + 60_000L)).isEqualTo(4_572_000L)
        assertThat(active.continuationCount).isEqualTo(3)
        assertThat(active.goalId).isEqualTo("8a1c")
        val paused = Goal.fromAccount(objective, GoalStatus.PAUSED, activeDurationMs = 5_100_000L, lastAccruedAtMs = t0, continuationCount = 0, goalId = "")
        assertThat(paused.accruingSinceMillis).isNull()
        assertThat(paused.elapsedMillis(t0 + 60_000L)).isEqualTo(5_100_000L)
        assertThat(paused.goalId).isNull()
        assertThat(paused.lastChange).isEqualTo(Goal.Change.Paused)
        assertThat(Goal.fromAccount(objective, GoalStatus.COMPLETE, null, null, 0, null).hasElapsed).isFalse()
    }
}
