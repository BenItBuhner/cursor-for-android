package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The coordinator transcript's presentation at sizes past the fixtures: N turns of a Project, most of them a worker's
 * report answered silently, one in nine with a `send_to_user` message, a prompt every fifty — timed cold, and warm
 * with one streaming delta appended (what each delta of a live coordinator turn costs the presenter). Every number is
 * printed as a `BENCH` line; the assertions hold the shape: the warm presentation is the cold one's, and a delta
 * rebuilds the newest turn alone.
 */
class CoordinatorFoldingBenchmarkTest {

    /** [leak]: every silent turn's log carries the last message again, as the seven-run frame's did (see `SevenRunCoordinator`). */
    private fun items(turns: Int, leak: Boolean = false): List<TimelineItem> {
        val out = ArrayList<TimelineItem>(turns * 4)
        var lastSend: ToolCall? = null
        val t0 = 1_800_000_000_000L - turns * 90_000L
        for (i in 0 until turns) {
            val at = t0 + i * 90_000L
            if (i % 50 == 0) {
                out += UserMessage("u-$i", "Prompt $i: keep the workers moving.", at)
            } else {
                out += SystemNotification("n-$i", SystemNotification.Kind.Worker, title = "Report $i", summary = "Worker ${i % 12} reported", raw = "title: Report $i\nsummary: Worker ${i % 12} reported", timestampMillis = at, agentId = "bc-w${i % 12}")
            }
            val steps = ArrayList<ActivityStep>()
            steps += ThinkingBlock("Reading the report of turn $i and deciding what the worker does next.", 3)
            repeat(3) { c ->
                steps += ToolCall("c-$i-$c", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "notes.md", detail = "notes/$c.md")
            }
            steps += ToolCall("c-$i-edit", "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "notes.md", detail = "notes/notes.md", linesAdded = 3, linesRemoved = 1)
            if (i % 9 == 0) {
                steps += ToolCall("c-$i-send", "send_to_user", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage("Turn $i: worker ${i % 12} finished its slice; next one started.", messageId = "m-$i")).also { lastSend = it }
            } else if (leak) {
                lastSend?.let { steps.add(1, it) }
            }
            out += ActivityGroup("g-$i", steps)
            out += RunFooter("f-$i", "run-$i", RunStatus.FINISHED, 60_000L, emptyList(), endedAtMillis = at + 60_000L)
        }
        return out
    }

    private inline fun ms(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    private fun report(scenario: String, metric: String, value: Any) = println("BENCH $scenario | $metric | $value")

    @Test
    fun `a streaming delta on a long Project's coordinator costs the newest turn, not the whole transcript`() {
        repeat(3) { TranscriptPresenter().present(items(240), coordinatorMode = true, runActive = false) }
        for (leak in listOf(false, true)) for (turns in listOf(240, 2_000, 5_000)) {
            val base = items(turns, leak)
            val scenario = "coordinator-fold turns=$turns${if (leak) " leaked" else ""}"
            val cold = List(5) { ms { TranscriptPresenter().present(base, coordinatorMode = true, runActive = false) } }
            val presenter = TranscriptPresenter()
            presenter.present(base, coordinatorMode = true, runActive = true)
            var text = ""
            var last: TranscriptPresenter.Presented? = null
            val deltas = List(40) { d ->
                text += "word$d "
                val live = base + AssistantMessage("live", text, isStreaming = true)
                ms { last = presenter.present(live, coordinatorMode = true, runActive = true) }
            }
            val leftOut = List(20) { ms { CoordinatorTranscript.leftOut(base) } }
            report(scenario, "items", base.size)
            report(scenario, "left out", CoordinatorTranscript.leftOut(base).size)
            report(scenario, "cold present median", "%.1f ms".format(median(cold)))
            report(scenario, "warm delta median", "%.2f ms".format(median(deltas)))
            report(scenario, "warm delta max", "%.2f ms".format(deltas.max()))
            report(scenario, "full leftOut scan median", "%.2f ms".format(median(leftOut)))

            val live = base + AssistantMessage("live", text, isStreaming = true)
            val fresh = TranscriptPresenter().present(live, coordinatorMode = true, runActive = true)
            assertThat(last!!.rows).isEqualTo(fresh.rows)
            assertThat(last!!.items).isEqualTo(fresh.items)
            assertThat(last!!.segmentsBuilt).isEqualTo(1)
        }
    }
}
