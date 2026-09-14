package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.fixtures.GoalFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The two account-service (Extended mode) readings of a goal, from the fixtures shaped as Cursor's own clients write
 * them (see [GoalFixtures]):
 *
 *  - the account's transcript (`FetchBackgroundComposer`), whose `ClientSideToolV2Call` records name the goal tools
 *    from the desktop's model-facing table (`create_goal`, `update_goal`) with `rawArgs = JSON.stringify(args)`;
 *  - the account's goal state (`GetLatestAgentConversationState` → `conversation_state.goal_state`), the record the
 *    desktop's goal tray reads, with the enum by name or number and the two `uint64` timings as decimal strings.
 */
class GoalRecordShapesTest {

    private val server = MockWebServer()
    private lateinit var record: HeadlessConversationApi
    private lateinit var steering: SteeringApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        val tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L })
        record = HeadlessConversationApi(rpc = ConnectJsonClient(client, base), tokens = tokens)
        steering = SteeringApi(rpc = ConnectJsonClient(client, base), tokens = tokens)
    }

    @After
    fun tearDown() = server.shutdown()

    private val objective = GoalFixtures.shape("createGoal", "args", "toJson").jsonObject.getValue("objective").jsonPrimitive.content

    private fun run(id: String, createdAt: String, durationMs: Long) = RunDto(id = id, agentId = "bc-goal", status = "FINISHED", createdAt = createdAt, updatedAt = createdAt, durationMs = durationMs)

    @Test
    fun `the record names the goal tools from the desktop's table and carries the arguments as the model wrote them`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody(GoalFixtures.text("goal_fetch_background_composer.json")))
        val page = record.fetch("bc-goal", startIndex = 0, limit = 50)
        assertThat(page.totalResponses).isEqualTo(11)
        val set = page.steps.first { it.toolCall?.name == "create_goal" }.toolCall!!
        assertThat(set.callId).isEqualTo("toolu_01Goal")
        assertThat(set.args!!.jsonObject.getValue("objective").jsonPrimitive.content).isEqualTo(objective)
        val update = page.steps.first { it.toolCall?.name == "update_goal" }.toolCall!!
        assertThat(update.args!!.jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("GOAL_STATUS_COMPLETE")
        // The continuation prompt is an injected turn, as the /v0 transcript would carry it.
        val prompts = page.steps.mapNotNull { it.userMessage }
        assertThat(prompts).hasSize(2)
        assertThat(SystemNotifications.isInjected(prompts[1])).isTrue()

        // Replayed through the same accumulator as the stream, the turns read as a goal set and then completed.
        val turns = HeadlessTranscript.split(page.steps)
        assertThat(turns).hasSize(2)
        val first = HeadlessTranscript.trace(turns[0], run("run-1", "2026-09-14T22:00:00.000Z", 42_000))
        val setCall = first.filterIsInstance<ActivityGroup>().flatMap { it.calls }.single { GoalTranscript.isGoalCall(it) }
        assertThat(setCall.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = objective))
        assertThat(setCall.action).isEqualTo("Created goal")
        val second = HeadlessTranscript.trace(turns[1], run("run-2", "2026-09-14T23:15:12.000Z", 18_000))
        val doneCall = second.filterIsInstance<ActivityGroup>().flatMap { it.calls }.single { GoalTranscript.isGoalCall(it) }
        assertThat(doneCall.payload).isEqualTo(ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Update, status = GoalStatus.COMPLETE))

        val t1 = 1_789_380_000_000L
        val t2 = t1 + 4_512_000L
        val items: List<TimelineItem> = listOf(com.cursorforandroid.domain.UserMessage("m1", turns[0].prompt!!, t1)) + first +
            SystemNotifications.parse("m2", turns[1].prompt!!, t2)!!.items + second
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal.status).isEqualTo(GoalStatus.COMPLETE)
        assertThat(goal.objective).isEqualTo(objective)
        assertThat(goal.activeDurationMs).isEqualTo(4_512_000L)
        assertThat(goal.continuationCount).isEqualTo(1)
        assertThat(GoalTranscript.lift(items).filterIsInstance<SystemNotification>().map { it.title }).containsExactly("Goal set", SystemNotifications.GOAL_CONTINUED, "Goal completed").inOrder()
    }

    /** The session is exchanged once per test and kept; every read after the first is the one RPC. */
    private var sessionIssued = false

    private suspend fun goalState(name: String): Goal? {
        if (!sessionIssued) {
            server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
            sessionIssued = true
        }
        server.enqueue(MockResponse().setBody(GoalFixtures.stateResponse(name)))
        val goal = steering.goal("bc-goal")
        var request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        if (request.path?.contains("exchange_user_api_key") == true) request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetLatestAgentConversationState")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("bcId").jsonPrimitive.content).isEqualTo("bc-goal")
        return goal
    }

    @Test
    fun `the account's goal state reads by field name, its timings from decimal strings and its status by name or number`() = runBlocking<Unit> {
        val active = goalState("active")!!
        assertThat(active).isEqualTo(
            Goal(
                objective = objective,
                status = GoalStatus.ACTIVE,
                activeDurationMs = 4_512_000L,
                accruingSinceMillis = 1_789_380_000_000L,
                continuationCount = 3,
                goalId = "8a1c3f52-6b7e-4d90-9f13-2e5c7a8b0d64",
                source = Goal.Source.Account,
                lastChange = Goal.Change.Set,
            ),
        )
        assertThat(active.isTicking).isTrue()
        assertThat(active.elapsedMillis(1_789_380_000_000L + 10_000L)).isEqualTo(4_522_000L)
        assertThat(goalState("activeAsInteger")).isEqualTo(active)
    }

    @Test
    fun `a paused or complete goal holds its accrued time still, and no goal state is no goal`() = runBlocking<Unit> {
        val paused = goalState("paused")!!
        assertThat(paused.status).isEqualTo(GoalStatus.PAUSED)
        assertThat(paused.activeDurationMs).isEqualTo(5_100_000L)
        assertThat(paused.accruingSinceMillis).isNull()
        assertThat(paused.isTicking).isFalse()
        val complete = goalState("complete")!!
        assertThat(complete.status).isEqualTo(GoalStatus.COMPLETE)
        assertThat(complete.status.isOpen).isFalse()
        assertThat(complete.activeDurationMs).isEqualTo(7_260_000L)
        assertThat(goalState("none")).isNull()
    }
}
