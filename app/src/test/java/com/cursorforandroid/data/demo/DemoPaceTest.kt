package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.RunStreamEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The realistic pace plays a scripted run the way a real model would, measured on the virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoPaceTest {

    private class Timed(val atMs: Long, val event: RunStreamEvent)

    private suspend fun TestScope.play(pace: DemoPace, agentId: String = "bc-demo-0003"): List<Timed> {
        val store = DemoStore()
        val runId = checkNotNull(store.agent(agentId)?.latestRunId)
        val events = mutableListOf<Timed>()
        DemoRunStreamer(store, pace).stream(agentId, runId, null).collect { events += Timed(currentTime, it) }
        return events
    }

    private fun RunStreamEvent.streamedText(): String? = when (this) {
        is RunStreamEvent.Assistant -> text
        is RunStreamEvent.Thinking -> text
        else -> null
    }

    @Test
    fun `streamed text never tops 100 tokens a second`() = runTest {
        val events = play(DemoPace.Realistic)
        val text = events.mapNotNull { e -> e.event.streamedText()?.let { e.atMs to it.length } }
        assertThat(text.sumOf { it.second }).isGreaterThan(2_000)
        // About four characters a token: no one-second window carries more than 400 characters.
        val busiest = text.maxOf { (start, _) -> text.filter { it.first in start until start + 1_000 }.sumOf { it.second } }
        assertThat(busiest).isAtMost(400)
        // And not a crawl either.
        assertThat(busiest).isAtLeast(250)
    }

    @Test
    fun `tool calls and subagents take believable time`() = runTest {
        val events = play(DemoPace.Realistic)
        val calls = events.mapNotNull { e -> (e.event as? RunStreamEvent.ToolCall)?.let { e.atMs to it.call } }
        val started = calls.filter { it.second.status == "running" }.associate { it.second.callId to it.first }
        val took = calls.filter { it.second.status == "completed" }.associate { it.second.callId to it.first - started.getValue(it.second.callId) }
        assertThat(took.values.min()).isAtLeast(1_000L)
        val subagents = calls.filter { it.second.name == "task" && it.second.status == "completed" }.map { took.getValue(it.second.callId) }
        assertThat(subagents).isNotEmpty()
        assertThat(subagents.min()).isAtLeast(10_000L)
        assertThat(events.last().atMs).isAtLeast(60_000L)
    }

    @Test
    fun `the marker picks the realistic pace and can only slow its steps`() {
        val dir = Files.createTempDirectory("pace").toFile()
        assertThat(DemoPace.fromMarker(dir)).isNull()
        val marker = File(dir, DemoPace.MARKER).apply { writeText("") }
        assertThat(DemoPace.fromMarker(dir)).isEqualTo(DemoPace.Realistic)
        marker.writeText("40\n")
        assertThat(DemoPace.fromMarker(dir)).isEqualTo(DemoPace.Realistic.copy(stepScale = 40.0))
        marker.writeText("1")
        assertThat(DemoPace.fromMarker(dir)).isEqualTo(DemoPace.Realistic)
        dir.deleteRecursively()
    }

    @Test
    fun `the brisk default is unchanged, for the tests that play whole runs`() = runTest {
        assertThat(play(DemoPace.Brisk).last().atMs).isLessThan(20_000L)
    }
}
