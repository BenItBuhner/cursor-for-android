package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A reply that is still being written is parsed once per delta. These pin that reading it incrementally gives the
 * same answer as reading it whole, and that the blocks above the one being written are not built again.
 */
class IncrementalMarkdownTest {

    private val reply = """
        # Result

        I changed **three** files and ran the suite.

        - `Markdown.kt` — the parser
        - `NavStack.kt` — the stack
          and its saver

        ```kotlin
        fun parse(markdown: String): List<MdBlock> {
            return emptyList()
        }
        ```

        > 520 tests pass.

        ---

        <img alt="Proof" src="/opt/cursor/artifacts/proof.png" />

        1. first
        2. second
    """.trimIndent()

    @Test
    fun `every prefix of a growing reply parses to what a whole-document parse would give`() {
        val incremental = IncrementalMarkdown()
        for (length in 0..reply.length) {
            val prefix = reply.substring(0, length)
            assertThat(incremental.parse(prefix)).isEqualTo(MarkdownParser.parse(prefix))
        }
    }

    @Test
    fun `the blocks above the one being written are kept, not built again`() {
        val incremental = IncrementalMarkdown()
        var previous: List<MdBlock> = emptyList()
        var longestKept = 0
        for (length in 0..reply.length) {
            val blocks = incremental.parse(reply.substring(0, length))
            var kept = 0
            while (kept < minOf(previous.size, blocks.size) && blocks[kept] === previous[kept]) kept++
            // Only the open block and the one the line being written could still join are read again.
            assertThat(kept).isAtLeast(previous.size - 3)
            longestKept = maxOf(longestKept, kept)
            previous = blocks
        }
        assertThat(longestKept).isAtLeast(6)
    }

    @Test
    fun `reading the same text twice does not parse it again`() {
        val incremental = IncrementalMarkdown()
        val first = incremental.parse(reply)
        assertThat(incremental.parse(reply)).isSameInstanceAs(first)
        assertThat(incremental.parse(String(reply.toCharArray()))).isSameInstanceAs(first)
    }

    @Test
    fun `text that is not an extension of what came before is parsed from the start`() {
        val incremental = IncrementalMarkdown()
        incremental.parse("one\n\ntwo\n\nthree")
        assertThat(incremental.parse("something else entirely"))
            .isEqualTo(MarkdownParser.parse("something else entirely"))
        assertThat(incremental.parse("one\n\ntwo")).isEqualTo(MarkdownParser.parse("one\n\ntwo"))
    }

    @Test
    fun `a block start is reported for every block, in source order`() {
        val parsed = MarkdownParser.parseWithStarts(reply)
        assertThat(parsed.starts).hasLength(parsed.blocks.size)
        assertThat(parsed.starts.toList()).isInOrder()
        assertThat(parsed.starts.first()).isEqualTo(0)
        parsed.starts.forEach { assertThat(it).isLessThan(reply.length) }
    }

    @Test
    fun `windows line endings do not shift the block starts`() {
        val crlf = reply.replace("\n", "\r\n")
        val parsed = MarkdownParser.parseWithStarts(crlf)
        assertThat(parsed.blocks).isEqualTo(MarkdownParser.parse(reply))
        parsed.blocks.indices.forEach { index ->
            // Each start is the offset of a line, so the text from there parses on its own.
            assertThat(MarkdownParser.parse(crlf.substring(parsed.starts[index]))).isEqualTo(parsed.blocks.drop(index))
        }
    }
}
