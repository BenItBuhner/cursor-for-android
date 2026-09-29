package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * A run with two calls under one [CoordinatorTranscript.messageKey] — the stream's positional ids
 * (`turn-N:step:M:tool`) come round — only one of which an earlier run drew: the other is the run's own. Reading it
 * threw `NoSuchElementException` (the run the run's own call was "first drawn in" looked up) on the whole scan, on
 * the resumed one and in the presenter, which dropped its kept scan and threw again on the next frame; or, when the
 * run's own call was not the group's first and every call of the group shared a replayed key, left the group out
 * whole, the run's own call with it.
 */
class ReplayedActivityDuplicateKeyTest {

    private fun read(id: String, file: String) = ToolCall(id, "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "$file.kt", detail = "src/$file.kt")

    private val first = read("turn-0:step:1:tool", "File1")
    private val third = read("turn-0:step:2:tool", "File3")
    /** The run's own call, under the id run A's [first] had. */
    private val second = read("turn-0:step:1:tool", "File2")
    /** The run's own call, under an id of its own. */
    private val fourth = read("turn-0:step:3:tool", "File4")

    /** Run A reads [first] and [third]; run B's log replays them among [runB]'s steps; run C edits. */
    private fun transcript(runB: List<ActivityStep>): List<TimelineItem> = listOf(
        UserMessage("u1", "Read the files", 1_000L),
        ActivityGroup("g-a", listOf(first, third)),
        AssistantMessage("a-a", "Read both files."),
        RunFooter("f-a", "run-A", RunStatus.FINISHED, 12_000L, emptyList()),
        UserMessage("u2", "And the next", 61_000L),
        ActivityGroup("g-b", runB),
        AssistantMessage("a-b", "Read both files."),
        RunFooter("f-b", "run-B", RunStatus.FINISHED, 9_000L, emptyList()),
        UserMessage("u3", "Now edit", 121_000L),
        ActivityGroup("g-c", listOf(ToolCall("c-edit", "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "notes.md", linesAdded = 1, linesRemoved = 1))),
        RunFooter("f-c", "run-C", RunStatus.FINISHED, 5_000L, emptyList()),
    )

    /** Run B's group, the calls it draws, and the label the assertions carry. */
    private val shapes = listOf(
        Triple("replay, own under the replay's key, replay, own", listOf(first.copy(), second, third.copy(), fourth), listOf("File1.kt", "File2.kt", "File4.kt")),
        Triple("own under the replay's key first", listOf(second, first.copy(), third.copy()), listOf("File2.kt", "File1.kt")),
        Triple("replay first, every call under a replayed key", listOf(first.copy(), second, third.copy()), listOf("File1.kt", "File2.kt")),
    )

    @Test
    fun `a replay sharing its key with the run's own call leaves out the rest of the replay and draws the run's own`() {
        for ((label, runB, drawn) in shapes) {
            val items = transcript(runB)
            // The replay under a key of its own goes; the key the replay shares with the run's own call is drawn, both
            // its calls with it — a key left out would take the run's own call with it.
            assertWithMessage(label).that(CoordinatorTranscript.replayedActivity(items)).containsExactly("g-b:turn-0:step:2:tool", "run:run-A")
            assertWithMessage(label).that(CoordinatorTranscript.leftOut(items)).containsExactly("g-b:turn-0:step:2:tool")

            val presented = CoordinatorTranscript.present(items, coordinatorMode = true)
            val groups = presented.filterIsInstance<ActivityGroup>().associate { group -> group.id to group.calls.map { it.summary } }
            assertWithMessage(label).that(groups).containsExactly("g-a", listOf("File1.kt", "File3.kt"), "g-b", drawn, "g-c", listOf("notes.md")).inOrder()
            // The run has a call of its own, so it keeps its text although run A said the same words.
            assertWithMessage(label).that(presented.filterIsInstance<AssistantMessage>().map { it.id }).containsExactly("a-a", "a-b").inOrder()
            assertWithMessage(label).that(presented.filterIsInstance<RunFooter>().map { it.runId }).containsExactly("run-A", "run-B", "run-C").inOrder()
        }
    }

    @Test
    fun `a scan resumed after any footer reads the duplicate key as the whole scan does`() {
        for ((label, runB, _) in shapes) {
            val items = transcript(runB)
            val whole = CoordinatorTranscript.leftOut(items)
            for (at in listOf(0) + items.indices.filter { items[it] is RunFooter }.map { it + 1 }) {
                val base = CoordinatorTranscript.LeftOutScan().apply { scan(items, 0, at) }
                val rest = CoordinatorTranscript.LeftOutScan(base).apply { scan(items, at, items.size) }
                assertWithMessage("$label resumed at $at").that(base.keys() + rest.keys()).isEqualTo(whole)
            }
        }
    }

    @Test
    fun `the presenter draws the rows of the whole presentation as the turns stream in and on every frame after`() {
        for ((label, runB, _) in shapes) {
            val items = transcript(runB)
            val presenter = TranscriptPresenter()
            val full = FullScanTranscriptPresenter()
            for (n in 1..items.size) {
                val shown = items.take(n)
                val presented = presenter.present(shown, coordinatorMode = true, runActive = false)
                val expected = GoalTranscript.lift(CoordinatorTranscript.present(shown, coordinatorMode = true))
                assertWithMessage("$label @$n").that(presented.items).isEqualTo(expected)
                assertWithMessage("$label @$n").that(presented.rows).isEqualTo(TranscriptRows.of(expected, coordinatorMode = true, runActive = false))
                assertWithMessage("$label @$n full scan").that(presented.rows).isEqualTo(full.present(shown, coordinatorMode = true, runActive = false).rows)
            }
            // Presented again, from the kept scan of the settled turns.
            val again = presenter.present(items, coordinatorMode = true, runActive = false)
            assertWithMessage("$label again").that(again.rows).isEqualTo(TranscriptRows.of(GoalTranscript.lift(CoordinatorTranscript.present(items, coordinatorMode = true)), coordinatorMode = true, runActive = false))
        }
    }
}
