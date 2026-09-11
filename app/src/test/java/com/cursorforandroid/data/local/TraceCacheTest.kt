package com.cursorforandroid.data.local

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the traces of one agent keep, and what a full file lets go of. */
class TraceCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache(maxBytesPerAgent: Int = 2 shl 20) = TraceCache(
        JsonDiskCache(folder.newFolder("traces"), dispatcher = Dispatchers.Unconfined),
        maxBytesPerAgent = maxBytesPerAgent,
    )

    private fun trace(runId: String, at: Long, reply: String = "Done.") = CachedTrace(
        runId = runId,
        createdAtMillis = at,
        items = listOf(AssistantMessage("$runId-a", reply)),
    )

    /**
     * The conversation pages up to eight pages of fifty runs and replays every finished one it fetched. Keeping
     * fewer than that meant a long chat replayed the same logs on every open and lost them once they expired.
     */
    @Test
    fun `every run the conversation can page to keeps its trace`() = runBlocking<Unit> {
        val traces = cache()

        traces.put("bc-1", (1..400).map { trace("run-$it", at = it.toLong()) })

        val kept = traces.read("bc-1")
        assertThat(kept).hasSize(400)
        assertThat(kept.keys).contains("run-1")
        assertThat(kept.keys).contains("run-400")
    }

    /** Past the run bound the oldest go, so the file cannot grow without end on a chat that never stops. */
    @Test
    fun `runs older than the paging bound are let go of`() = runBlocking<Unit> {
        val traces = cache()

        traces.put("bc-1", (1..420).map { trace("run-$it", at = it.toLong()) })

        val kept = traces.read("bc-1")
        assertThat(kept).hasSize(400)
        assertThat(kept.keys).doesNotContain("run-1")
        assertThat(kept.keys).doesNotContain("run-20")
        assertThat(kept.keys).contains("run-21")
        assertThat(kept.keys).contains("run-420")
    }

    /**
     * A trace is as long as the run's log, so four hundred of them is not a bounded file. The newest that fit the
     * budget are kept.
     */
    @Test
    fun `traces past the byte budget are dropped oldest first`() = runBlocking<Unit> {
        val traces = cache(maxBytesPerAgent = 4_000)

        traces.put("bc-1", (1..20).map { trace("run-$it", at = it.toLong(), reply = "x".repeat(1_000)) })

        val kept = traces.read("bc-1")
        assertThat(kept.size).isAtLeast(1)
        assertThat(kept.size).isLessThan(20)
        assertThat(kept.keys).contains("run-20")
        assertThat(kept.keys).doesNotContain("run-1")
    }

    /** One run whose trace is bigger than the whole budget is still kept: the run just watched is always there. */
    @Test
    fun `a single trace larger than the budget is still kept`() = runBlocking<Unit> {
        val traces = cache(maxBytesPerAgent = 100)

        traces.put("bc-1", listOf(trace("run-1", at = 1L, reply = "y".repeat(5_000))))

        assertThat(traces.read("bc-1").keys).containsExactly("run-1")
    }

    /**
     * A tool call is read off its JSON while it is built and carries only what a row shows, so what the disk keeps is
     * that and nothing else: no whole files, no whole command output.
     */
    @Test
    fun `a tool call reaches the disk as the row it renders`() = runBlocking<Unit> {
        val traces = cache()
        val group = ActivityGroup(
            id = "group-1",
            steps = listOf(
                ThinkingBlock("Looking at the file."),
                ToolCall(
                    callId = "call-1",
                    name = "run_terminal_cmd",
                    kind = ToolKind.Shell,
                    status = ToolCall.STATUS_COMPLETED,
                    summary = "ls",
                    detail = "ls -la",
                    output = "total 8\nREADME.md",
                    exitCode = 0,
                ),
            ),
        )

        traces.put("bc-1", listOf(CachedTrace("run-1", 1L, listOf(group))))

        val call = (traces.read("bc-1").getValue("run-1").items.single() as ActivityGroup).calls.single()
        assertThat(call.summary).isEqualTo("ls")
        assertThat(call.detail).isEqualTo("ls -la")
        assertThat(call.output).isEqualTo("total 8\nREADME.md")
        assertThat(call.exitCode).isEqualTo(0)
    }
}
