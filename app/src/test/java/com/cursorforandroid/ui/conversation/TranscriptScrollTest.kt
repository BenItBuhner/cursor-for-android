package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * What the transcript's list holds on to while a run streams: the keys it anchors by stay the same through every
 * event of a turn, and an item's index in either order names the same item (see [TranscriptScroll], [TranscriptOrder]).
 */
class TranscriptScrollTest {

    private fun rowsOf(run: TimelineBuilder.LiveRun) =
        TranscriptRows.of(listOf(UserMessage("u1", "Read the modules")) + run.snapshot(), coordinatorMode = false, runActive = true)

    private fun read(n: Int) = RunStreamEvent.ToolCall(SseToolCallDto(callId = "c$n", name = "read_file", status = "completed", args = buildJsonObject { put("path", JsonPrimitive("File$n.kt")) }))

    @Test
    fun `the live stretch and every step in it keep their keys through a streamed turn`() {
        val run = TimelineBuilder.LiveRun("run-1", timed = false)
        run.apply(RunStreamEvent.Thinking("Reading the first module."))
        val first = rowsOf(run).last()
        // A lone thought is its own row until the first call, and keeps the key it had once it is a stretch of steps.
        val keys = mutableMapOf<String, String>()
        for (step in 1..5) {
            repeat(10) { line ->
                run.apply(RunStreamEvent.Thinking("Step $step line $line.\n"))
                val live = rowsOf(run).last() as TranscriptRow.Stretch
                assertThat(live.key).isEqualTo(first.key)
                assertThat(live.live).isTrue()
                live.listed.forEachIndexed { i, entry -> assertThat(keys.getOrPut("$i") { entry.key }).isEqualTo(entry.key) }
            }
            run.apply(read(step))
            val live = rowsOf(run).last() as TranscriptRow.Stretch
            assertThat(live.key).isEqualTo(first.key)
            live.listed.forEachIndexed { i, entry -> assertThat(keys.getOrPut("$i") { entry.key }).isEqualTo(entry.key) }
        }
        assertThat((rowsOf(run).last() as TranscriptRow.Stretch).listed).hasSize(10)
    }

    @Test
    fun `a stretch that opens on an injected turn keeps its key when the next one folds the two into a group`() {
        fun event(id: String) = SystemNotification(id, SystemNotification.Kind.Subagent, "Subagent completed", "Worker $id", raw = "<system_notification/>")
        val prompt = UserMessage("u1", "Coordinate the workers")
        val work = ActivityGroup("g1", listOf(ThinkingBlock("Checking on the workers.", isStreaming = true)))
        val one = TranscriptRows.of(listOf(prompt, event("e1"), work), coordinatorMode = true, runActive = true).last() as TranscriptRow.Stretch
        val two = TranscriptRows.of(listOf(prompt, event("e1"), event("e2"), work), coordinatorMode = true, runActive = true).last() as TranscriptRow.Stretch
        assertThat(two.entries.first()).isInstanceOf(TranscriptRow.Entry.Events::class.java)
        assertThat(two.key).isEqualTo(one.key)
    }

    @Test
    fun `an item's index in one order and its key at the other order's index agree`() {
        val rows = TranscriptRows.of(listOf(UserMessage("u1", "a"), UserMessage("u2", "b"), UserMessage("u3", "c")), coordinatorMode = false)
        val order = TranscriptOrder(above = listOf("older", "traces"), rows = rows, below = listOf("working"))
        val topDown = listOf("older", "traces", "u1", "u2", "u3", "working")
        assertThat(order.size).isEqualTo(topDown.size)
        topDown.forEachIndexed { i, key ->
            assertThat(order.index(key, following = false)).isEqualTo(i)
            assertThat(order.index(key, following = true)).isEqualTo(topDown.lastIndex - i)
            assertThat(order.keyAt(i, following = false)).isEqualTo(key)
            assertThat(order.keyAt(topDown.lastIndex - i, following = true)).isEqualTo(key)
        }
        assertThat(order.index("gone", following = false)).isNull()
        assertThat(order.keyAt(topDown.size, following = true)).isNull()
    }
}
