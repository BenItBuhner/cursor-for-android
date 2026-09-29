package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a streaming publication costs the presenter in a long chat, against what the passes that used to read the
 * whole transcript on every publication — the chat's mode by its content, its goal, the subagent index — cost when
 * they read it all. A turn is a prompt, a thought and twelve reads (and a `SendMessage` in a coordinator's chat), a
 * reply and a footer; a goal is set in the first turn and a task delegated every fiftieth. The newest reply streams to
 * 30 KB, every other instance kept. Every number is printed as a `BENCH` line; nothing is asserted on time.
 */
class PresenterSegmentPassesBenchmarkTest {

    /** The current thread's allocation counter, where the JVM has one (read reflectively: the Android stubs hide it). */
    private val allocated: () -> Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        { method.invoke(bean, id) as Long }
    }.getOrDefault { 0L }

    private fun turns(count: Int, coordinator: Boolean): List<TimelineItem> = (0 until count).flatMap { t ->
        val at = 1_000_000L + 60_000L * t
        val steps = ArrayList<ActivityStep>()
        steps += ThinkingBlock("thinking $t", 2)
        if (t == 0) steps += ToolCall("goal", "CreateGoal", ToolKind.Other, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.GoalChange(ToolPayload.GoalChange.Action.Set, objective = "Ship it"))
        if (t % 50 == 1) steps += ToolCall("task$t", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "Look into $t", payload = ToolPayload.Subagent("Look into $t", agentId = "sub$t"))
        (0 until 12).forEach { k -> steps += ToolCall("c$t-$k", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "File$k.kt") }
        if (coordinator) steps += ToolCall("m$t", "SendMessage", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage("Turn $t is done."))
        listOf(
            UserMessage("u$t", "Prompt $t", at),
            ActivityGroup("g$t", steps),
            AssistantMessage("a$t", "Reply $t with `code`"),
            RunFooter("f$t", "run$t", RunStatus.FINISHED, 12_000L, emptyList(), endedAtMillis = at + 12_000L),
        )
    }

    private class Cost(val ms: Double, val maxMs: Double, val mb: Double) {
        override fun toString() = "${"%.3f".format(ms)}ms (max ${"%.2f".format(maxMs)}) ${"%.2f".format(mb)}MB"
    }

    private fun measure(warmup: Int, reps: Int, pass: (Int) -> Unit): Cost {
        repeat(warmup) { pass(it) }
        val bytes = allocated()
        var total = 0L
        var max = 0L
        repeat(reps) {
            val startedAt = System.nanoTime()
            pass(warmup + it)
            val took = System.nanoTime() - startedAt
            total += took
            max = maxOf(max, took)
        }
        return Cost(total / 1e6 / reps, max / 1e6, (allocated() - bytes) / 1e6 / reps)
    }

    @Test
    fun `a streaming publication against the whole-transcript passes`() {
        for (coordinator in listOf(false, true)) for (count in listOf(1500, 6000)) {
            val settled = turns(count, coordinator)
            // The newest turn is still being written: its reply streams, its footer is not there yet.
            val head = settled.dropLast(2)
            val chunk = "word ".repeat(30)
            fun streamed(i: Int): List<TimelineItem> = head + AssistantMessage("a-live", chunk.repeat(1 + i % 200), isStreaming = true)
            val presenter = TranscriptPresenter()
            presenter.present(streamed(0), coordinatorMode = coordinator, runActive = true)
            val delta = measure(50, 200) { i -> presenter.present(streamed(i + 1), coordinatorMode = coordinator, runActive = true) }
            val items = streamed(7)
            val rows = presenter.present(items, coordinatorMode = coordinator, runActive = true).rows
            val mode = measure(20, 50) { CoordinatorTranscript.hasCoordinatorContent(items) }
            val goal = measure(20, 50) { GoalTranscript.derive(items) }
            val index = measure(20, 50) { SubagentRows.index(rows) }
            assertThat(presenter.present(items, coordinatorMode = coordinator, runActive = true).goal).isEqualTo(GoalTranscript.derive(items))
            val chat = if (coordinator) "coordinator" else "ordinary"
            println("BENCH segment-passes chat=$chat turns=$count items=${items.size} present-delta=$delta whole-hasCoordinatorContent=$mode whole-goal=$goal whole-subagent-index=$index")
        }
    }
}
