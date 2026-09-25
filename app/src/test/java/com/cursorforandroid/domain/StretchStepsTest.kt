package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StretchStepsTest {

    private fun call(n: Int) = TranscriptRow.Entry.Call(ToolCall("c$n", "read_file", ToolKind.Read, "completed", "File$n.kt"), "c$n")
    private fun thought(key: String, text: String, streaming: Boolean = false) = TranscriptRow.Entry.Thought(ThinkingBlock(text, isStreaming = streaming), key)

    private fun lines(prefix: String, chars: Int) = buildString {
        var n = 0
        while (length < chars) {
            if (isNotEmpty()) append(if (n % 4 == 0) "\n\n" else "\n")
            append("$prefix line ${++n}: what the list measures on a frame and which rows lay out again.")
        }
    }

    @Test
    fun `a short thought is one piece`() {
        assertThat(ThoughtChunks.split("thinking")).containsExactly("thinking")
    }

    @Test
    fun `the pieces of a long thought stack into its lines`() {
        val text = lines("Weighing", 300_000)
        val parts = ThoughtChunks.split(text)
        assertThat(parts.size).isGreaterThan(100)
        assertThat(parts.joinToString("\n")).isEqualTo(text)
        assertThat(parts.maxOf { it.length }).isAtMost(ThoughtChunks.MAX_LINE)
    }

    @Test
    fun `a line longer than a piece is cut at a space`() {
        val text = "word ".repeat(3_000).trim()
        val parts = ThoughtChunks.split(text)
        assertThat(parts.size).isGreaterThan(1)
        assertThat(parts.joinToString(" ")).isEqualTo(text)
        assertThat(parts.maxOf { it.length }).isAtMost(ThoughtChunks.MAX_LINE)
    }

    @Test
    fun `the cuts behind a growing thought's tail stay where they were`() {
        val whole = lines("Streaming", 120_000)
        var earlier = ThoughtChunks.split(whole.substring(0, 20_000))
        for (end in 20_800..whole.length step 800) {
            val now = ThoughtChunks.split(whole.substring(0, end))
            val settled = earlier.dropLast(3)
            assertThat(now.take(settled.size)).isEqualTo(settled)
            earlier = now
        }
    }

    @Test
    fun `nothing open lists the rows as they were`() {
        val rows = listOf<TranscriptRow>(TranscriptRow.Stretch(listOf(call(1), call(2))))
        val listed = StretchSteps.list(rows, { StretchSteps.Open.Closed })
        assertThat(listed.rows).isSameInstanceAs(rows)
        assertThat(listed.steps).isEmpty()
    }

    @Test
    fun `an open stretch lists its steps after it with the column's spacing`() {
        val stretch = TranscriptRow.Stretch(listOf(thought("t1", "short thought"), call(1), call(2)))
        val after = TranscriptRow.Stretch(listOf(call(3), call(4)))
        val rows = listOf<TranscriptRow>(stretch, after)
        val listed = StretchSteps.list(rows, { if (it === stretch) StretchSteps.Open.Steps else StretchSteps.Open.Closed }).rows
        assertThat(listed.map { it.key }).containsExactly(stretch.key, "step:t1#0", "step:c1", after.key).inOrder()
        val steps = listed.filterIsInstance<TranscriptRow.Step>()
        assertThat(steps.map { it.gapAbove }).containsExactly(StretchSteps.FIRST_GAP, StretchSteps.GAP).inOrder()
        assertThat(steps.map { it.padTop }).containsExactly(StretchSteps.THOUGHT_PAD, 0).inOrder()
        assertThat(steps.map { it.padBottom }).containsExactly(StretchSteps.THOUGHT_PAD, 0).inOrder()
        assertThat(steps.map { it.endPad }).containsExactly(0, StretchSteps.END_PAD).inOrder()
        assertThat(steps.first().text).isEqualTo("short thought")
        assertThat(steps.last().entries.map { it.key }).containsExactly("c1", "c2").inOrder()
        assertThat(steps.all { it.stretchKey == stretch.key }).isTrue()
    }

    @Test
    fun `calls go a chunk to an item, and a thought starts a new one`() {
        val chunk = StretchSteps.CHUNK
        val entries = (1..2 * chunk + 4).map { call(it) } + thought("t1", "between") + (1..3).map { call(1_000 + it) }
        val stretch = TranscriptRow.Stretch(entries)
        val steps = StretchSteps.list(listOf(stretch), { StretchSteps.Open.Steps }).rows.filterIsInstance<TranscriptRow.Step>()
        assertThat(steps.map { it.key }).containsExactly("step:c1", "step:c${chunk + 1}", "step:c${2 * chunk + 1}", "step:t1#0", "step:c1001").inOrder()
        assertThat(steps.map { it.entries.size }).containsExactly(chunk, chunk, 4, 0, 3).inOrder()
        assertThat(steps.map { it.gapAbove }).containsExactly(StretchSteps.FIRST_GAP, StretchSteps.GAP, StretchSteps.GAP, StretchSteps.GAP, StretchSteps.GAP).inOrder()
        assertThat(steps.map { it.padTop }).containsExactly(0, 0, 0, StretchSteps.THOUGHT_PAD, 0).inOrder()
    }

    @Test
    fun `a huge thought is listed in pieces, spaced as one`() {
        val text = lines("Huge", 50_000)
        val stretch = TranscriptRow.Stretch(listOf(thought("t1", text)))
        val steps = StretchSteps.list(listOf(stretch), { StretchSteps.Open.Thought }).rows.filterIsInstance<TranscriptRow.Step>()
        assertThat(steps.size).isGreaterThan(10)
        assertThat(steps.joinToString("\n") { it.text!! }).isEqualTo(text)
        assertThat(steps.first().gapAbove).isEqualTo(StretchSteps.FIRST_GAP)
        assertThat(steps.drop(1).all { it.gapAbove == 0 }).isTrue()
        assertThat(steps.last().padBottom).isEqualTo(StretchSteps.END_PAD)
        assertThat(steps.dropLast(1).all { it.padBottom == 0 }).isTrue()
    }

    @Test
    fun `a full chunk that says the same is the last answer's instance`() {
        val chunk = StretchSteps.CHUNK
        val first = TranscriptRow.Stretch((1..chunk + 2).map { call(it) }, live = true)
        val a = StretchSteps.list(listOf(first), { StretchSteps.Open.Steps })
        val grown = TranscriptRow.Stretch((1..chunk + 3).map { call(it) }, live = true)
        val b = StretchSteps.list(listOf(grown), { StretchSteps.Open.Steps }, a.steps)
        assertThat(b.steps.getValue("step:c1")).isSameInstanceAs(a.steps.getValue("step:c1"))
        // The last chunk took the new call, and carries the end padding still.
        assertThat(b.steps.getValue("step:c${chunk + 1}").entries).hasSize(3)
        assertThat(b.steps.getValue("step:c${chunk + 1}").endPad).isEqualTo(StretchSteps.END_PAD)
        assertThat(b.steps.values.all { it.live }).isTrue()
    }

    @Test
    fun `a thought made anew with the same text is not cut again`() {
        val text = lines("Again", 40_000)
        val parts = thought("t1", text).parts
        assertThat(thought("t1", String(text.toCharArray())).parts).isSameInstanceAs(parts)
        assertThat(parts).isEqualTo(ThoughtChunks.split(text.trim()))
    }
}
