package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * What [ShownMessages.keep] costs one publication of a long coordinator chat: it runs under the conversation's lock,
 * so every stream event of the chat waits on it. Printed as `BENCH` lines; the bound asserted is loose, for a shared
 * CI machine, and still well under what the whole-list scan for each remembered message cost (50-100 ms at 6000 turns).
 */
class ShownMessagesBenchmarkTest {

    private fun chat(turns: Int): List<TimelineItem> = (0 until turns).flatMap { i ->
        val work = ActivityGroup("w$i", (0 until 3).map { s -> ToolCall("w$i-$s", "read_file", ToolKind.Read, "completed", "src/File$s.kt") })
        val text = "Turn $i: " + "the scanner read forty markets and flagged nothing for you; the next pass starts at noon. ".repeat(1 + i % 3)
        val message = ActivityGroup("m$i", listOf(ToolCall("c$i", "sendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage(text))))
        listOf(UserMessage("rec-prompt-$i", "Where are we on step $i?"), work, message, RunFooter("f$i", "run-$i", RunStatus.FINISHED, 3_000L, emptyList()))
    }

    @Test
    fun `keeping the shown messages of a long coordinator chat`() {
        for (turns in listOf(1_500, 6_000)) {
            val items = chat(turns)
            val shown = ShownMessages()
            repeat(20) { shown.keep(items, "record") }
            val runs = 50
            var total = 0L
            var max = 0L
            repeat(runs) {
                val started = System.nanoTime()
                shown.keep(items, "record")
                val took = System.nanoTime() - started
                total += took
                max = maxOf(max, took)
            }
            val avgMs = total / runs / 1e6
            println("BENCH shownMessages | turns=$turns keep avg | ${"%.2f".format(avgMs)} ms (max ${"%.2f".format(max / 1e6)} ms)")
            if (turns == 6_000) assertWithMessage("keep at $turns turns, ms per publication").that(avgMs).isLessThan(30.0)
        }
    }
}
