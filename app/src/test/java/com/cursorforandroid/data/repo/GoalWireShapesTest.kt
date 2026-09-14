package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.SseToolCallTruncationDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.GoalFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Test

/**
 * The goal tools as the documented stream carries them, frame for frame, from the fixtures generated off Cursor's own
 * protos (see [GoalFixtures]): `CreateGoal` is `createGoal` in the SDK's vocabulary with `CreateGoalArgs {objective}`
 * and an empty success; `UpdateGoal` is `updateGoal` with `UpdateGoalArgs {status}`, the status an enum written by
 * name (`GOAL_STATUS_COMPLETE`) or, by an encoder set to, by number; either result's error case is `GoalError {error}`
 * under the oneof's `error`. The desktop's own table names the same tools `create_goal` / `update_goal`.
 */
class GoalWireShapesTest {

    private val objective = GoalFixtures.shape("createGoal", "args", "toJson").jsonObject.getValue("objective").jsonPrimitive.content

    private fun events(fixture: String): List<RunStreamEvent> {
        val source = Buffer().writeUtf8(GoalFixtures.text(fixture))
        val events = ArrayList<RunStreamEvent>()
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.let { events += it.event }
        }
        return events
    }

    private fun replay(fixture: String, runId: String): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun(runId, timed = false)
        events(fixture).forEach(live::apply)
        return live.snapshot()
    }

    private fun calls(items: List<TimelineItem>): Map<String, ToolCall> = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }

    @Test
    fun `the fixtures are whole runs whose tool_call frames decode under both names`() {
        val set = events("goal_run.sse")
        assertThat(set.filterIsInstance<RunStreamEvent.ToolCall>().map { it.call.name }.distinct()).containsExactly("createGoal", "read", "shell").inOrder()
        assertThat(set.last()).isEqualTo(RunStreamEvent.Done)
        val done = events("goal_completed_run.sse")
        assertThat(done.filterIsInstance<RunStreamEvent.ToolCall>().map { it.call.name }.distinct()).containsExactly("shell", "updateGoal").inOrder()
        val refused = events("goal_refused_run.sse")
        assertThat(refused.filterIsInstance<RunStreamEvent.ToolCall>().map { it.call.name }.distinct()).containsExactly("create_goal")
    }

    @Test
    fun `the tools are known under the SDK's, the desktop's and the prompt's spellings`() {
        for (name in listOf("createGoal", "create_goal", "CreateGoal", "createGoalToolCall", "create_goal_tool_call")) {
            assertThat(ToolNames.goalTool(name)).isEqualTo(ToolPayload.GoalChange.Action.Set)
            assertThat(ToolNames.kindOf(name)).isEqualTo(ToolKind.Other)
        }
        for (name in listOf("updateGoal", "update_goal", "UpdateGoal", "updateGoalToolCall")) {
            assertThat(ToolNames.goalTool(name)).isEqualTo(ToolPayload.GoalChange.Action.Update)
        }
        assertThat(ToolNames.goalTool("sendMessage")).isNull()
        assertThat(ToolNames.goalTool("read")).isNull()
        // The desktop's own verbs for the two, under the names the stream carries.
        assertThat(ToolNames.labels(ToolKind.Other, "createGoal").completed).isEqualTo("Created goal")
        assertThat(ToolNames.labels(ToolKind.Other, "update_goal").loading).isEqualTo("Updating goal")
        // The fixture's own account of the vocabulary agrees.
        val names = GoalFixtures.shape("names").jsonObject
        assertThat(names.getValue("sdk").toString()).isEqualTo("""["createGoal","updateGoal"]""")
        assertThat(names.getValue("desktopTable").toString()).isEqualTo("""["create_goal","update_goal"]""")
    }

    @Test
    fun `CreateGoal carries the objective under objective, and the call keeps it verbatim`() {
        val call = calls(replay("goal_run.sse", "run-goal-001")).getValue("goal-c1")
        assertThat(call.kind).isEqualTo(ToolKind.Other)
        assertThat(call.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = objective))
        assertThat(call.action).isEqualTo("Created goal")
        assertThat(call.summary).isEqualTo(objective.take(45) + "...")
        assertThat(call.detail).isEqualTo(objective)
        assertThat(call.argKeys).containsExactly("objective")
        assertThat(call.isError).isFalse()
    }

    @Test
    fun `every serialisation of CreateGoalArgs reads the same objective`() {
        for (way in listOf("toJson", "stringify", "snake")) {
            val args = GoalFixtures.shape("createGoal", "args", way)
            val payload = ToolPayloads.from("createGoal", args, GoalFixtures.shape("createGoal", "success", way)) as ToolPayload.GoalChange
            assertThat(payload.objective).isEqualTo(objective)
            assertThat(payload.error).isNull()
            assertThat(payload.missing).isFalse()
            assertThat(payload.resultingStatus).isEqualTo(GoalStatus.ACTIVE)
        }
    }

    @Test
    fun `UpdateGoalArgs name the status by enum name or by number, and the success repeats it`() {
        val byName = ToolPayloads.from("updateGoal", GoalFixtures.shape("updateGoal", "args", "complete", "toJson"), GoalFixtures.shape("updateGoal", "success", "complete", "toJson")) as ToolPayload.GoalChange
        assertThat(byName.status).isEqualTo(GoalStatus.COMPLETE)
        assertThat(byName.resultingStatus).isEqualTo(GoalStatus.COMPLETE)
        val byNumber = ToolPayloads.from("update_goal", GoalFixtures.shape("updateGoal", "args", "completeAsInteger"), null) as ToolPayload.GoalChange
        assertThat(byNumber.status).isEqualTo(GoalStatus.COMPLETE)
        assertThat((ToolPayloads.from("updateGoal", GoalFixtures.shape("updateGoal", "args", "paused", "stringify"), null) as ToolPayload.GoalChange).status).isEqualTo(GoalStatus.PAUSED)
        assertThat((ToolPayloads.from("updateGoal", GoalFixtures.shape("updateGoal", "args", "active", "snake"), null) as ToolPayload.GoalChange).status).isEqualTo(GoalStatus.ACTIVE)
        assertThat((ToolPayloads.from("updateGoal", GoalFixtures.shape("updateGoal", "args", "cleared", "toJson"), null) as ToolPayload.GoalChange).status).isEqualTo(GoalStatus.CLEARED)
        // The status can come from the success alone when the arguments were left out.
        val fromResult = ToolPayloads.from("updateGoal", null, GoalFixtures.shape("updateGoal", "success", "paused", "toJson")) as ToolPayload.GoalChange
        assertThat(fromResult.status).isEqualTo(GoalStatus.PAUSED)
        // The enum's every spelling.
        assertThat(GoalStatus.parse("GOAL_STATUS_ACTIVE")).isEqualTo(GoalStatus.ACTIVE)
        assertThat(GoalStatus.parse("paused")).isEqualTo(GoalStatus.PAUSED)
        assertThat(GoalStatus.parse("4")).isEqualTo(GoalStatus.CLEARED)
        assertThat(GoalStatus.parse("GOAL_STATUS_UNSPECIFIED")).isNull()
        assertThat(GoalStatus.parse(null as String?)).isNull()
        val table = GoalFixtures.shape("goalStatus").jsonObject
        GoalStatus.entries.forEach { status -> assertThat(table.getValue(status.wireName).jsonPrimitive.content.toInt()).isEqualTo(status.number) }
    }

    @Test
    fun `the completing turn's updateGoal is a completed call with the status, and the run reads as complete`() {
        val items = replay("goal_completed_run.sse", "run-goal-004")
        val call = calls(items).getValue("goal-c2")
        assertThat(call.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Update, status = GoalStatus.COMPLETE))
        assertThat(call.action).isEqualTo("Updated goal")
        assertThat(call.summary).isEqualTo("complete")
        assertThat(call.isError).isFalse()
    }

    @Test
    fun `a refused goal is an error call that keeps the objective and the account's reason`() {
        val items = replay("goal_refused_run.sse", "run-goal-009")
        val call = calls(items).getValue("goal-c9")
        assertThat(call.isError).isTrue()
        assertThat(call.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = objective, error = "A goal is already active on this conversation"))
        assertThat(call.action).isEqualTo("Create goal")
        // The refusal changes no goal: the transcript has none to show.
        assertThat(GoalTranscript.derive(items)).isNull()
        // Its row says so, in the account's words.
        val row = GoalTranscript.lift(items).filterIsInstance<SystemNotification>().single()
        assertThat(row.title).isEqualTo("Goal not set")
        assertThat(row.tone).isEqualTo(NoticeTone.Error)
        assertThat(row.summary).isEqualTo("A goal is already active on this conversation")
        assertThat(row.body).isEqualTo(objective)
        // Every serialisation of the error reads the same.
        for (way in listOf("toJson", "stringify", "snake")) {
            val payload = ToolPayloads.from("updateGoal", GoalFixtures.shape("updateGoal", "args", "complete", way), GoalFixtures.shape("updateGoal", "error", way)) as ToolPayload.GoalChange
            assertThat(payload.error).isEqualTo("No active goal to update")
        }
    }

    @Test
    fun `arguments the stream left out still make a goal being set, marked as missing its objective`() {
        val dto = SseToolCallDto("c-trunc", "createGoal", ToolCall.STATUS_COMPLETED, args = null, result = GoalFixtures.shape("createGoal", "success", "toJson"), truncated = SseToolCallTruncationDto(args = true))
        val call = ToolCallMapper.from(dto)
        assertThat(call.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, missing = true))
        val items = listOf(ActivityGroup("g", listOf(call)))
        // The stream itself left it out: a replay would bring the same frame, so this is not a trace to refresh.
        assertThat(GoalTranscript.needsRefresh(items)).isFalse()
        val row = GoalTranscript.lift(items).filterIsInstance<SystemNotification>().single()
        assertThat(row.title).isEqualTo("Goal set")
        assertThat(row.summary).isEqualTo(GoalTranscript.OBJECTIVE_MISSING)
    }

    @Test
    fun `the goal-setting run derives an active goal from the turn's start, lifted into a row above the work`() {
        val prompt = UserMessage("m1", "/goal $objective", timestampMillis = 1_789_380_000_000L)
        val items = listOf(prompt) + replay("goal_run.sse", "run-goal-001")
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal).isEqualTo(Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = 1_789_380_000_000L, lastChange = Goal.Change.Set))
        assertThat(goal.label).isEqualTo("Goal active")
        assertThat(goal.isTicking).isTrue()
        assertThat(goal.elapsedMillis(1_789_380_000_000L + 4_512_000L)).isEqualTo(4_512_000L)

        val lifted = GoalTranscript.lift(items)
        // The prompt, the thought (a group of its own before the goal call), the goal's row, the work after it, the reply, the footer.
        val row = lifted.filterIsInstance<SystemNotification>().single()
        assertThat(row.kind).isEqualTo(SystemNotification.Kind.Goal)
        assertThat(row.title).isEqualTo("Goal set")
        assertThat(row.summary).isEqualTo(objective)
        assertThat(row.body).isEqualTo(objective)
        assertThat(row.raw).isEqualTo(objective)
        assertThat(row.timestampMillis).isEqualTo(1_789_380_000_000L)
        val groups = lifted.filterIsInstance<ActivityGroup>()
        assertThat(groups).hasSize(2)
        assertThat(groups[0].calls).isEmpty()
        assertThat(groups[0].thoughts).hasSize(1)
        assertThat(groups[1].calls.map { it.name }).containsExactly("read", "shell").inOrder()
        assertThat(groups.map { it.id }.toSet()).hasSize(2)
        assertThat(lifted.indexOf(row)).isEqualTo(lifted.indexOf(groups[0]) + 1)
        assertThat(lifted.indexOf(groups[1])).isEqualTo(lifted.indexOf(row) + 1)
        // Nothing of the goal is left behind in the work.
        assertThat(groups.flatMap { it.calls }.none(GoalTranscript::isGoalCall)).isTrue()
        // A transcript with no goal call is handed back as it is.
        val plain = replay("goal_completed_run.sse", "run-goal-004").filterNot { it is ActivityGroup }
        assertThat(GoalTranscript.lift(plain)).isSameInstanceAs(plain)
    }

    @Test
    fun `the objective survives every JSON shape of the arguments as an object`() {
        val shapes: List<JsonObject> = listOf("toJson", "stringify", "snake").map { GoalFixtures.shape("createGoal", "args", it).jsonObject }
        assertThat(shapes.map { it.getValue("objective").jsonPrimitive.content }.distinct()).containsExactly(objective)
    }
}
