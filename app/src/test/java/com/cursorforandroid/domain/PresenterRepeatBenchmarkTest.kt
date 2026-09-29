package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the presenter costs for a state that moved only a flag (loading older turns, the queue) against one that
 * moved an item (a live delta), in a long chat: a prompt, a thought and twelve reads, a reply and a footer a turn.
 * Every number is printed as a `BENCH` line; nothing is asserted on time.
 */
class PresenterRepeatBenchmarkTest {

    /** The current thread's allocation counter, where the JVM has one (read reflectively: the Android stubs hide it). */
    private val allocated: () -> Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        { method.invoke(bean, id) as Long }
    }.getOrDefault { 0L }

    private fun turns(count: Int): List<TimelineItem> = (0 until count).flatMap { t ->
        val at = 1_000_000L + 60_000L * t
        listOf(
            UserMessage("u$t", "Prompt $t", at),
            ActivityGroup("g$t", listOf(ThinkingBlock("thinking $t", 2)) + (0 until 12).map { k -> ToolCall("c$t-$k", "read_file", ToolKind.Read, "completed", "File$k.kt") }),
            AssistantMessage("a$t", "Reply $t with `code`"),
            RunFooter("f$t", "run$t", RunStatus.FINISHED, 12_000L, emptyList(), endedAtMillis = at + 12_000L),
        )
    }

    private class Cost(val ms: Double, val mb: Double)

    private fun measure(reps: Int, pass: (Int) -> Unit): Cost {
        repeat(reps) { pass(it) }
        val bytes = allocated()
        val startedAt = System.nanoTime()
        repeat(reps) { pass(reps + it) }
        return Cost((System.nanoTime() - startedAt) / 1e6 / reps, (allocated() - bytes) / 1e6 / reps)
    }

    @Test
    fun `the same items presented again against a live delta`() {
        for (count in listOf(1500, 6000)) {
            val items = turns(count)
            val presenter = TranscriptPresenter()
            val first = presenter.present(items, coordinatorMode = false, runActive = true)
            val delta = measure(20) { i ->
                presenter.present(items.dropLast(2) + AssistantMessage("a-live", "word ".repeat(i + 1), isStreaming = true) + items.last(), coordinatorMode = false, runActive = true)
            }
            presenter.present(items, coordinatorMode = false, runActive = true)
            val again = measure(20) { presenter.present(items, coordinatorMode = false, runActive = true) }
            assertThat(presenter.present(items, coordinatorMode = false, runActive = true).rows).isEqualTo(first.rows)
            println("BENCH presenter turns=$count items=${items.size} same-items-again=${"%.3f".format(again.ms)}ms ${"%.2f".format(again.mb)}MB live-delta=${"%.3f".format(delta.ms)}ms ${"%.2f".format(delta.mb)}MB")
        }
    }
}
