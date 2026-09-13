package com.cursorforandroid.notifications

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.domain.WidgetList
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Nothing of a Project notifies — not its coordinator, not a worker, a side chat or a subagent — and none of them
 * reaches the recents or the widget. The rule is read at every point a card could come from: the decision that starts
 * the service, the monitor that follows runs and reports finishes, the watchdog that stands in for the service, and
 * the primary surfaces. This drives each of them through the way the classification actually arrives in each mode:
 * in default mode from the coordinator's own transcript, whose `create_agent` / `send_to_agent` calls name its workers
 * (the one lineage signal the documented API carries); in Extended mode from the account's list, which says of every
 * chat whether it is a Project and whose child it is. Robolectric only because [SessionManager] needs a Context.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectNotificationIsolationTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val finished = CopyOnWriteArrayList<TrackedRun>()
    private val announced = CopyOnWriteArrayList<TrackedRun>()

    private lateinit var prefs: PreferencesStore
    private lateinit var attachments: AttachmentStore
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var monitor: RunMonitor
    private lateinit var conversations: ConversationRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments)
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        monitor = RunMonitor(agents, hub, runRecord = { agentId, runId -> api.getRun(agentId, runId) }, refreshIntervalMs = 600_000, nowProvider = { now })
        conversations = ConversationRepository(session, agents, prefs, hub, attachments)
        scope.launch { monitor.finished.collect { finished += it } }
        // Four running chats the list cannot tell apart yet: a coordinator, its worker, a side chat, a chat of the account's own.
        api.addRunningAgent("bc-p", "Cesium billing launch", "run-p")
        api.addRunningAgent("bc-w", "Stripe webhook handler", "run-w")
        api.addRunningAgent("bc-s", "Pricing copy", "run-s")
        api.addRunningAgent("bc-x", "Plain chat", "run-x")
        agents.refresh()
    }

    @After
    fun tearDown() {
        monitor.stop()
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun decision() = liveDecision(session.state.value, agents.state.value, enabled = true, serviceActive = false)

    private fun watchdog() = FinishWatchdog(
        session = session,
        agents = agents,
        prefs = prefs,
        runRecord = { agentId, runId -> api.getRun(agentId, runId) },
        isServiceActive = { false },
        canShowLive = { true },
        announce = { announced += it; true },
        nowProvider = { now },
        announced = mutableSetOf(),
    )

    private fun finishOnServer(agentId: String, runId: String) {
        api.runs[runId] = api.runs.getValue(runId).copy(status = "FINISHED", result = "Done.", durationMs = 60_000, updatedAt = "2026-04-13T18:31:00.000Z")
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "IDLE")
        api.v0[agentId] = api.v0.getValue(agentId).copy(status = "FINISHED")
    }

    /** What every surface says once the Project's chats are known for what they are. */
    private suspend fun assertNothingOfTheProjectNotifies() {
        assertThat(agents.agent("bc-p")!!.isProjectScoped).isTrue()
        assertThat(agents.agent("bc-w")!!.isProjectChild).isTrue()
        assertThat(agents.agent("bc-x")!!.isProjectScoped).isFalse()
        // The decision that starts the foreground service counts only the account's own chat.
        assertThat(decision()).isEqualTo(LiveDecision.Track(setOf("bc-x"), serviceActive = false))
        // The monitor follows only that chat and never reports the others' finishes.
        monitor.start()
        awaitUntil { monitor.state.value.hasReconciled }
        assertThat(monitor.state.value.running.map { it.agentId }).containsExactly("bc-x")
        assertThat(monitor.state.value.runningCount).isEqualTo(1)
        finishOnServer("bc-w", "run-w")
        finishOnServer("bc-p", "run-p")
        agents.refresh(silent = true)
        streamer.emit("run-x", RunStreamEvent.Result("run-x", RunStatus.FINISHED, "Done.", 10_000, null))
        streamer.emit("run-x", RunStreamEvent.Done)
        awaitUntil { finished.size == 1 }
        delay(100)
        assertThat(finished.map { it.agentId }).containsExactly("bc-x")
        // The watchdog, standing in for the service, announces none of them either — only a chat of the account's
        // own that finished while nothing followed it — and has nothing left to watch once that chat is told, the
        // side chat still running notwithstanding.
        monitor.stop()
        api.addRunningAgent("bc-y", "Second plain chat", "run-y")
        agents.refresh(silent = true)
        finishOnServer("bc-y", "run-y")
        assertThat(watchdog().check()).isEqualTo(FinishWatchdog.Outcome.Done)
        assertThat(announced.map { it.agentId }).containsExactly("bc-y")
        // The primary surfaces list only the account's own chat, whatever its state.
        val all = agents.state.value.agents
        val recent = WidgetList.rows(WidgetMode.Recent, all, ListPreferences(), LocalAgentState(), nowMillis = now, zone = ZoneOffset.UTC)
        assertThat(recent.map { it.agent.id }).containsExactly("bc-x", "bc-y")
        val running = WidgetList.rows(WidgetMode.Running, all, ListPreferences(), LocalAgentState(), nowMillis = now, zone = ZoneOffset.UTC)
        assertThat(running).isEmpty()
        assertThat(all.first { it.id == "bc-s" }.isRunning).isTrue()
    }

    @Test
    fun `default mode - the coordinator's transcript classifies its workers, and from then on none of the Project notifies`() = runBlocking {
        // Before anything is known every running chat counts, as any chat of the account's would.
        assertThat(decision()).isEqualTo(LiveDecision.Track(setOf("bc-p", "bc-w", "bc-s", "bc-x"), serviceActive = false))

        // The user opens the coordinator's chat; its stream carries the coordinator's tools, which name the workers.
        conversations.attach("bc-p")
        awaitUntil { streamer.connections.contains("run-p") }
        streamer.emit("run-p", RunStreamEvent.Status("run-p", RunStatus.RUNNING))
        streamer.emit(
            "run-p",
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = "c1",
                    name = "create_agent",
                    status = "completed",
                    args = buildJsonObject { put("name", JsonPrimitive("Stripe webhook handler")); put("prompt", JsonPrimitive("Handle the webhooks.")) },
                    result = buildJsonObject { putJsonObject("success") { put("agent_id", JsonPrimitive("bc-w")); put("message", JsonPrimitive("Created")) } },
                ),
            ),
        )
        streamer.emit(
            "run-p",
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = "c2",
                    name = "send_to_agent",
                    status = "completed",
                    args = buildJsonObject { put("agent_id", JsonPrimitive("bc-s")); put("message", JsonPrimitive("Draft the pricing copy.")) },
                    result = buildJsonObject { putJsonObject("success") { put("worker_bc_id", JsonPrimitive("bc-s")); put("delivered_as", JsonPrimitive("followup")) } },
                ),
            ),
        )
        awaitUntil { agents.agent("bc-p")?.scope == AgentScope.PROJECT_ROOT && agents.agent("bc-w")?.isProjectChild == true && agents.agent("bc-s")?.isProjectChild == true }
        conversations.detach("bc-p")
        assertNothingOfTheProjectNotifies()
    }

    @Test
    fun `extended mode - the account's list classifies the Project and its children, and none of them notifies`() = runBlocking {
        // What `ListBackgroundComposers{include_workers, include_subagents, include_hidden_sources}` says of each row.
        agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-p", isProject = true),
                ComposerSnapshot("bc-w", parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)),
                ComposerSnapshot("bc-s", source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD),
                ComposerSnapshot("bc-x"),
            ),
        )
        assertThat(agents.agent("bc-p")!!.scope).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(agents.agent("bc-s")!!.scope).isEqualTo(AgentScope.PROJECT_CHILD)
        assertNothingOfTheProjectNotifies()
    }

    @Test
    fun `a signed-in decision is the same rule - the scope, not the mode, is what is read`() {
        val signedIn = session.state.value
        assertThat(signedIn).isInstanceOf(SessionState.SignedIn::class.java)
        agents.applyLineage("bc-p", mapOf("bc-w" to AgentParentKind.PROJECT_WORKER, "bc-s" to AgentParentKind.SIDE_CHAT), authoritative = false)
        assertThat(decision()).isEqualTo(LiveDecision.Track(setOf("bc-x"), serviceActive = false))
        // Every chat placed inside a Project, and the Project itself, is off the notification surfaces.
        val state = agents.state.value
        assertThat(state.agents.filter { it.isProjectScoped }.map { it.id }).containsExactly("bc-p", "bc-w", "bc-s")
        assertThat(liveDecision(signedIn, state.copy(agents = state.agents.filter { it.isProjectScoped }), enabled = true, serviceActive = true)).isEqualTo(LiveDecision.Idle)
    }
}
