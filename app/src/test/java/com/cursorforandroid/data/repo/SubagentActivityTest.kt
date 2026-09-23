package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A cloud subagent's row follows it live: the list's row for how its latest run stands, and while that run goes, its
 * stream for the step it announced and the action it is on — the line under the row's title, moving as the child works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentActivityTest {

    private fun agent(status: RunStatus, runId: String = "run-1", modelId: String? = "composer-2.5") = Agent(
        id = "bc-w1", name = "Usage events aggregation", lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/bc-w1", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = runId, repoUrl = null, startingRef = null, modelId = modelId,
    )

    private fun call(id: String, kind: ToolKind, summary: String, name: String = kind.name.lowercase()) = ToolCall(id, name, kind, ToolCall.STATUS_RUNNING, summary)

    @Test
    fun `the action line moves with the child's stream, and its end settles the row`() = runTest(UnconfinedTestDispatcher()) {
        val row = MutableStateFlow<Agent?>(agent(RunStatus.RUNNING))
        val stream = MutableStateFlow(SubagentActivity.Run(emptyList(), finished = false, status = RunStatus.RUNNING))
        val followed = mutableListOf<String>()
        val activity = SubagentActivity(
            row = { row },
            load = { error("the list holds it") },
            run = { _, runId -> followed += runId; stream },
            models = MutableStateFlow(LiveModelCatalog.models),
        )
        val seen = mutableListOf<SubagentChild?>()
        val job = launch { activity.of("bc-w1").collect { seen += it } }

        // The list's row: running, on Composer 2.5 with Fast, nothing said yet.
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Running)
        assertThat(seen.last()!!.model?.label).isEqualTo("Composer 2.5")
        assertThat(seen.last()!!.model?.fast).isTrue()
        assertThat(seen.last()!!.action).isNull()
        assertThat(followed).containsExactly("run-1")

        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("e1", ToolKind.Edit, "Chart.kt")))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.action).isEqualTo("Editing Chart.kt")
        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("e1", ToolKind.Edit, "Chart.kt"), call("w1", ToolKind.WebSearch, "compose shimmer", "web_search")))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.action).isEqualTo("Searching web compose shimmer")
        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("u1", ToolKind.Other, "Wiring the hover state", "update_current_step"), ThinkingBlock("…", isStreaming = true)))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.step).isEqualTo("Wiring the hover state")
        assertThat(seen.last()!!.action).isEqualTo("Thinking")

        // The run ends: the list's row says so, and the stream is let go.
        row.value = agent(RunStatus.FINISHED)
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Succeeded)
        // A new turn (a steer, a queued message): the new run is followed.
        row.value = agent(RunStatus.RUNNING, runId = "run-2")
        assertThat(followed).containsExactly("run-1", "run-2").inOrder()
        job.cancel()
    }

    @Test
    fun `a child the list does not hold is read once, and a finished one is never streamed`() = runTest(UnconfinedTestDispatcher()) {
        var loads = 0
        val activity = SubagentActivity(
            row = { flowOf(null) },
            load = { loads++; agent(RunStatus.ERROR) },
            run = { _, _ -> error("a finished run is not streamed") },
            models = MutableStateFlow(emptyList<ModelOption>()),
        )
        val seen = mutableListOf<SubagentChild?>()
        val job = launch { activity.of("bc-w1").collect { seen += it } }
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Failed)
        assertThat(seen.last()!!.name).isEqualTo("Usage events aggregation")
        assertThat(loads).isEqualTo(1)
        job.cancel()
    }
}
