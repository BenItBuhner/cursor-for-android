package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A reply that is still being written is parsed once per delta. These pin that reading it incrementally gives the
 * same answer as reading it whole, and that the blocks above the one being written are not built again.
 */
class IncrementalMarkdownTest {

    /** Every block kind the parser knows, including the ones that look ahead a line (tables) or behind (setext). */
    private val reply = """
        # Result

        I changed **three** files and ran the suite.

        - `Markdown.kt` — the parser
        - `NavStack.kt` — the stack
          and its saver
          - nested under it
        - [x] recorded the screenshots

        ```kotlin
        fun parse(markdown: String): List<MdBlock> {
            return emptyList()
        }
        ```

        > **Note**
        > - 520 tests pass
        still quoted

        ---

        <img alt="Proof" src="/opt/cursor/artifacts/proof.png" />

        1. Install:

           ```bash
           npm install
           ```

        2. Run it

        Where the money went
        | Wallet | USD |
        |:---|---:|
        | `0x4b3f` | 2,407,993 |
        Trailing sentence.

        Closing
        =======

        3. third
        4. fourth
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
    fun `every prefix of a reply with windows line endings parses to what a whole-document parse would give`() {
        val crlf = reply.replace("\n", "\r\n")
        val incremental = IncrementalMarkdown()
        for (length in 0..crlf.length) {
            val prefix = crlf.substring(0, length)
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
        assertThat(longestKept).isAtLeast(8)
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

    // --- The line being written re-shaping the block above it, with settled blocks in front -------------------------

    @Test
    fun `a heading that turns out to be prose rejoins the paragraph before it`() {
        // "#" alone is an empty heading; "#hashtag" is not a heading and so continues the paragraph above.
        val incremental = IncrementalMarkdown()
        val settled = "Intro\n\nMore intro\n\n"
        incremental.parse(settled + "Para\n#")
        val whole = settled + "Para\n#hashtag"
        assertThat(incremental.parse(whole)).isEqualTo(MarkdownParser.parse(whole))
        assertThat(incremental.parse(whole).last()).isEqualTo(MdBlock.Paragraph("Para #hashtag"))
    }

    @Test
    fun `a delimiter row that stops being one turns the table back into the paragraph's next line`() {
        val incremental = IncrementalMarkdown()
        val settled = "Intro\n\nMore intro\n\n"
        val withTable = incremental.parse(settled + "Para\n| a | b |\n|---|-")
        assertThat(withTable.last()).isInstanceOf(MdBlock.Table::class.java)
        val whole = settled + "Para\n| a | b |\n|---|-x|"
        assertThat(incremental.parse(whole)).isEqualTo(MarkdownParser.parse(whole))
        assertThat(incremental.parse(whole).last()).isEqualTo(MdBlock.Paragraph("Para | a | b | |---|-x|"))
    }

    @Test
    fun `a list that goes on after a blank line and a row that joins a table stay one block`() {
        val incremental = IncrementalMarkdown()
        val settled = "Intro\n\nMore intro\n\n"
        val list = settled + "- a\n\n- b\n\n"
        incremental.parse(list)
        assertThat(incremental.parse(list + "- c")).isEqualTo(MarkdownParser.parse(list + "- c"))
        val table = settled + "| a | b |\n|---|---|\n| 1 | 2 |\nTrailing"
        incremental.parse(table)
        assertThat(incremental.parse("$table | 3 |")).isEqualTo(MarkdownParser.parse("$table | 3 |"))
    }

    // --- Block starts --------------------------------------------------------------------------------------------------

    @Test
    fun `a block start is reported for every block, in source order`() {
        val parsed = MarkdownParser.parseWithStarts(reply)
        assertThat(parsed.blocks).isEqualTo(MarkdownParser.parse(reply))
        assertThat(parsed.starts).hasLength(parsed.blocks.size)
        assertThat(parsed.starts.toList()).isInOrder()
        assertThat(parsed.starts.first()).isEqualTo(0)
        parsed.starts.forEach { assertThat(it).isLessThan(reply.length) }
        parsed.blocks.indices.forEach { index ->
            // Each start is the offset of a line, so the text from there parses on its own.
            assertThat(MarkdownParser.parse(reply.substring(parsed.starts[index]))).isEqualTo(parsed.blocks.drop(index))
        }
    }

    @Test
    fun `a setext heading and a table start where their first line does, a split paragraph shares its start`() {
        val parsed = MarkdownParser.parseWithStarts("Title\n=====\n\nWho\n| a |\n|-|\n\nsee <img src=\"x.png\"> here")
        assertThat(parsed.blocks.map { it::class.simpleName }).containsExactly("Heading", "Paragraph", "Table", "Paragraph", "Image", "Paragraph").inOrder()
        assertThat(parsed.starts.toList()).containsExactly(0, 13, 17, 28, 28, 28).inOrder()
    }

    @Test
    fun `windows line endings do not shift the block starts`() {
        val crlf = reply.replace("\n", "\r\n")
        val parsed = MarkdownParser.parseWithStarts(crlf)
        assertThat(parsed.blocks).isEqualTo(MarkdownParser.parse(reply))
        parsed.blocks.indices.forEach { index ->
            assertThat(MarkdownParser.parse(crlf.substring(parsed.starts[index]))).isEqualTo(parsed.blocks.drop(index))
        }
    }
}
