package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SpotlightContent
import com.cursorforandroid.domain.SpotlightTarget
import com.cursorforandroid.domain.TimelineItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** What the Spotlight follows for a chat and for a Project, and when it lets go. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotlightFeedTest {

    private fun agent(
        id: String,
        running: Boolean = true,
        project: Boolean = false,
        parent: String? = null,
        runId: String? = "run-$id",
        updatedAt: Long = 0L,
        name: String = id,
    ) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = 0L,
        updatedAtMillis = updatedAt,
        latestRunId = runId,
        repoUrl = null,
        startingRef = null,
        isProject = project,
        parent = parent?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) },
    )

    private fun items(vararg events: RunStreamEvent): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun("run") { 0L }
        events.forEach { live.apply(it) }
        return live.snapshot()
    }

    private fun editing(path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = path, name = "edit_file", status = "running", args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    /** The hub as the feed sees it: one snapshot flow per run, and a record of who subscribed how. */
    private inner class Hub {
        val runs = mutableMapOf<String, MutableStateFlow<LiveRunHub.Snapshot>>()
        val subscriptions = mutableListOf<Pair<String, Boolean>>()
        val open = mutableSetOf<String>()

        fun of(agentId: String, runId: String): MutableStateFlow<LiveRunHub.Snapshot> =
            runs.getOrPut(agentId) { MutableStateFlow(LiveRunHub.Snapshot(agentId, runId, startedAtMillis = 10L)) }

        fun run(agentId: String, runId: String, @Suppress("UNUSED_PARAMETER") startedAt: Long?, watched: Boolean): Flow<LiveRunHub.Snapshot> =
            of(agentId, runId)
                .onStart { subscriptions += agentId to watched; open += agentId }
                .onCompletion { open -= agentId }
    }

    private val rows = MutableStateFlow<List<Agent>>(emptyList())
    private val loaded = mutableMapOf<String, Agent>()
    private val hub = Hub()
    private val feed = SpotlightFeed(rows, { loaded[it] }, hub::run, projectGraceMs = 30_000L, maxMembers = 2)

    private fun TestScope.collect(target: SpotlightTarget): MutableList<SpotlightFeed.Frame> {
        val frames = mutableListOf<SpotlightFeed.Frame>()
        backgroundScope.launch { feed.of(target).collect { frames += it } }
        runCurrent()
        return frames
    }

    private fun chat(id: String) = SpotlightTarget(id, isProject = false, startedAtMillis = 1L)
    private fun project(id: String) = SpotlightTarget(id, isProject = true, startedAtMillis = 1L)
    private fun List<SpotlightFeed.Frame>.lastLive() = filterIsInstance<SpotlightFeed.Frame.Live>().last()

    @Test
    fun `a chat at rest has nothing to spotlight`() = runTest {
        rows.value = listOf(agent("a", running = false))
        assertThat(collect(chat("a"))).containsExactly(SpotlightFeed.Frame.Ended)
        assertThat(hub.subscriptions).isEmpty()
    }

    @Test
    fun `a running chat is streamed as watched, frame by frame, until its run finishes`() = runTest {
        rows.value = listOf(agent("a", name = "Fix login"))
        val frames = collect(chat("a"))
        assertThat(hub.subscriptions).containsExactly("a" to true)
        assertThat(frames.lastLive().view.step).isEqualTo("Starting\u2026")
        assertThat(frames.lastLive().covered).containsExactly("a")

        hub.of("a", "run-a").value = hub.of("a", "run-a").value.copy(items = items(editing("app/Login.kt")), eventCount = 1)
        runCurrent()
        assertThat(frames.lastLive().view.step).isEqualTo("Editing Login.kt")
        assertThat(frames.lastLive().view.title).isEqualTo("Fix login")

        // Renamed mid-run: the next frame carries the list's name.
        rows.value = listOf(agent("a", name = "Fix login flow"))
        runCurrent()
        assertThat(frames.lastLive().view.title).isEqualTo("Fix login flow")

        hub.of("a", "run-a").value = hub.of("a", "run-a").value.copy(finished = true, status = RunStatus.FINISHED)
        runCurrent()
        val finished = frames.last() as SpotlightFeed.Frame.Finished
        assertThat(finished.agent.name).isEqualTo("Fix login flow")
        assertThat(finished.snapshot.status).isEqualTo(RunStatus.FINISHED)
        assertThat(hub.open).isEmpty()
    }

    @Test
    fun `a chat the list has no run for is loaded first`() = runTest {
        rows.value = listOf(agent("a", runId = null))
        loaded["a"] = agent("a", runId = "run-9")
        collect(chat("a"))
        assertThat(hub.runs["a"]?.value?.runId).isEqualTo("run-9")
    }

    @Test
    fun `a Project follows its coordinator watched and at most maxMembers running chats unwatched`() = runTest {
        rows.value = listOf(
            agent("p", project = true, name = "Launch"),
            agent("w1", parent = "p", updatedAt = 3, name = "Onboarding"),
            agent("w2", parent = "p", updatedAt = 2, name = "Pricing"),
            agent("w3", parent = "p", updatedAt = 1, name = "Docs"),
            agent("w4", parent = "p", running = false),
            agent("other"),
        )
        val frames = collect(project("p"))
        assertThat(hub.subscriptions).containsExactly("p" to true, "w1" to false, "w2" to false)
        val live = frames.lastLive()
        assertThat(live.covered).containsExactly("p", "w1", "w2", "w3")
        assertThat(live.view.title).isEqualTo("Launch")
        assertThat(live.view.lines).containsExactly(
            "\u2022 Onboarding \u00B7 Starting\u2026",
            "\u2022 Pricing \u00B7 Starting\u2026",
            "\u2022 +1 more",
        ).inOrder()
        assertThat(live.view.tally).isEqualTo("3 of 4 chats running")

        hub.of("w2", "run-w2").value = hub.of("w2", "run-w2").value.copy(items = items(editing("web/Pricing.tsx")), eventCount = 1)
        runCurrent()
        assertThat(frames.lastLive().view.lines[1]).isEqualTo("\u2022 Pricing \u00B7 Editing Pricing.tsx")
    }

    @Test
    fun `a list refresh that changes nothing shown does not restart the streams`() = runTest {
        rows.value = listOf(agent("p", project = true), agent("w1", parent = "p"), agent("other", updatedAt = 1))
        collect(project("p"))
        val before = hub.subscriptions.size
        rows.value = listOf(agent("p", project = true), agent("w1", parent = "p"), agent("other", updatedAt = 2), agent("new"))
        runCurrent()
        assertThat(hub.subscriptions).hasSize(before)
    }

    @Test
    fun `a Project that goes quiet waits out its grace, then ends`() = runTest {
        rows.value = listOf(agent("p", project = true), agent("w1", parent = "p"))
        val frames = collect(project("p"))
        rows.value = listOf(agent("p", project = true, running = false), agent("w1", parent = "p", running = false))
        runCurrent()
        assertThat(frames.lastLive().view.step).isEqualTo(SpotlightContent.WAITING)
        assertThat(hub.open).isEmpty()

        advanceTimeBy(29_000L)
        runCurrent()
        assertThat(frames.last()).isNotEqualTo(SpotlightFeed.Frame.Ended)
        advanceTimeBy(2_000L)
        runCurrent()
        assertThat(frames.last()).isEqualTo(SpotlightFeed.Frame.Ended)
    }

    @Test
    fun `a new turn inside the grace keeps the Project in the Spotlight`() = runTest {
        rows.value = listOf(agent("p", project = true, running = false), agent("w1", parent = "p", running = false))
        val frames = collect(project("p"))
        advanceTimeBy(10_000L)
        rows.value = listOf(agent("p", project = true, runId = "run-p2"), agent("w1", parent = "p", running = false))
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(frames).doesNotContain(SpotlightFeed.Frame.Ended)
        assertThat(frames.lastLive().view.step).isEqualTo("Starting\u2026")
    }

    @Test
    fun `an archived or vanished Project ends the Spotlight`() = runTest {
        rows.value = listOf(agent("p", project = true))
        val frames = collect(project("p"))
        rows.value = emptyList()
        runCurrent()
        assertThat(frames.last()).isEqualTo(SpotlightFeed.Frame.Ended)
    }
}
