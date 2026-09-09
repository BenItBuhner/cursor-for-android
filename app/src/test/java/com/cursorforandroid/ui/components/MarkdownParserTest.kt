package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings, paragraphs, lists, code fences and quotes`() {
        val md = """
            # Title
            Some **bold** text with `code` and a [link](https://cursor.com).

            - one
            - two
              continued
            1. first
            2. second

            ```kotlin
            val x = 1
            ```
            > quoted line
            > continues
            ---
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Heading", "Paragraph", "Bullets", "Bullets", "Code", "Quote", "Rule").inOrder()
        assertThat((blocks[0] as MdBlock.Heading).level).isEqualTo(1)
        val bullets = blocks[2] as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(MdItem("one"), MdItem("two continued")).inOrder()
        val ordered = blocks[3] as MdBlock.Bullets
        assertThat(ordered.items).containsExactly(MdItem("first", number = 1), MdItem("second", number = 2)).inOrder()
        val code = blocks[4] as MdBlock.Code
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("val x = 1")
        assertThat((blocks[5] as MdBlock.Quote).text).isEqualTo("quoted line continues")
    }

    @Test
    fun `consecutive lines join into one paragraph and blank lines split`() {
        val blocks = MarkdownParser.parse("line one\nline two\n\nline three")
        assertThat(blocks).hasSize(2)
        assertThat((blocks[0] as MdBlock.Paragraph).text).isEqualTo("line one line two")
    }

    @Test
    fun `unterminated code fence still yields a code block`() {
        val blocks = MarkdownParser.parse("```\nfoo\nbar")
        assertThat((blocks.single() as MdBlock.Code).code).isEqualTo("foo\nbar")
    }

    @Test
    fun `html media tags in a reply become image and video blocks`() {
        // The shape of a cloud agent's "Proof" section, verbatim.
        val md = """
            I rendered the composable through Robolectric at ten consecutive ticks.

            <img alt="Web recording vs Android RunningGlyph, all 8 steps" src="/opt/cursor/artifacts/running_glyph_web_vs_android_8_steps.png" />

            <video src="/opt/cursor/artifacts/running_glyph_web_vs_android_side_by_side.mp4"></video>

            Compile, lint, 46/46 unit tests pass. PR: [#14](https://github.com/o/r/pull/14).
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Paragraph", "Image", "Video", "Paragraph").inOrder()
        val image = blocks[1] as MdBlock.Image
        assertThat(image.src).isEqualTo("/opt/cursor/artifacts/running_glyph_web_vs_android_8_steps.png")
        assertThat(image.alt).isEqualTo("Web recording vs Android RunningGlyph, all 8 steps")
        assertThat((blocks[2] as MdBlock.Video).src).isEqualTo("/opt/cursor/artifacts/running_glyph_web_vs_android_side_by_side.mp4")
        assertThat((blocks[3] as MdBlock.Paragraph).text).startsWith("Compile, lint")
    }

    @Test
    fun `media inside a paragraph splits the surrounding text and a multi-line video tag is one block`() {
        val blocks = MarkdownParser.parse("Before ![Alt](https://x.test/a.png) after\n<video controls>\n  <source src=\"clip.mp4\">\n</video>")
        assertThat(blocks).containsExactly(
            MdBlock.Paragraph("Before"),
            MdBlock.Image("https://x.test/a.png", "Alt"),
            MdBlock.Paragraph("after"),
            MdBlock.Video("clip.mp4", null),
        ).inOrder()
    }

    @Test
    fun `list items keep their raw text so the renderer can split media per item`() {
        val blocks = MarkdownParser.parse("- Before: <img src=\"a.png\">\n- After: <img src=\"b.png\">")
        val bullets = blocks.single() as MdBlock.Bullets
        assertThat(bullets.items.map { it.text })
            .containsExactly("Before: <img src=\"a.png\">", "After: <img src=\"b.png\">").inOrder()
    }

    @Test
    fun `a nested list keeps its levels instead of flattening`() {
        val md = """
            - top level
              - nested
                - deeper
              - back to nested
            - back to top
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(
            MdItem("top level", depth = 0),
            MdItem("nested", depth = 1),
            MdItem("deeper", depth = 2),
            MdItem("back to nested", depth = 1),
            MdItem("back to top", depth = 0),
        ).inOrder()
    }

    @Test
    fun `an ordered list nested under a bullet is a list, not literal text`() {
        val md = """
            - steps
              1. first
              2. second
            - done
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(
            MdItem("steps", depth = 0),
            MdItem("first", depth = 1, number = 1),
            MdItem("second", depth = 1, number = 2),
            MdItem("done", depth = 0),
        ).inOrder()
    }

    @Test
    fun `a list that continues a sequence keeps the numbers it was written with`() {
        val bullets = MarkdownParser.parse("3. third\n4. fourth\n5. fifth").single() as MdBlock.Bullets
        assertThat(bullets.items.map { it.number }).containsExactly(3, 4, 5).inOrder()
    }

    @Test
    fun `every item written as 1 still counts up`() {
        // The usual way to write a numbered list without renumbering it by hand.
        val bullets = MarkdownParser.parse("1. a\n1. b\n1. c").single() as MdBlock.Bullets
        assertThat(bullets.items.map { it.number }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `an outer list resumes its own count after a sublist`() {
        val md = """
            1. one
               1. inner
               2. inner two
            2. two
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(
            MdItem("one", depth = 0, number = 1),
            MdItem("inner", depth = 1, number = 1),
            MdItem("inner two", depth = 1, number = 2),
            MdItem("two", depth = 0, number = 2),
        ).inOrder()
    }

    @Test
    fun `swapping the marker at a nested level starts a new list from the number written there`() {
        val md = """
            - outer
              3. a
              - b
              7. c
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(
            MdItem("outer", depth = 0),
            MdItem("a", depth = 1, number = 3),
            // The bullet does not advance the count it interrupts, and the ordered item after it numbers itself.
            MdItem("b", depth = 1),
            MdItem("c", depth = 1, number = 7),
        ).inOrder()
    }

    @Test
    fun `a bullet sublist between two ordered ones does not spend the outer numbers`() {
        val md = """
            1. one
               - note
               - other note
            2. two
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items.map { it.number }).containsExactly(1, null, null, 2).inOrder()
    }

    @Test
    fun `wrapped text still belongs to the item above it`() {
        val md = """
            - top
              wrapped onto the next line
              - nested
                also wrapped
        """.trimIndent()
        val bullets = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(bullets.items).containsExactly(
            MdItem("top wrapped onto the next line", depth = 0),
            MdItem("nested also wrapped", depth = 1),
        ).inOrder()
    }

    @Test
    fun `a rule is any run of three or more markers`() {
        val rules = listOf("---", "----", "***", "___", "- - -", "* * *", "  ---")
        for (line in rules) {
            assertThat(MarkdownParser.parse(line)).containsExactly(MdBlock.Rule)
        }
    }

    @Test
    fun `two markers are not a rule`() {
        // "--" and a lone "-" stay prose; "- x" is still a list.
        assertThat(MarkdownParser.parse("--")).containsExactly(MdBlock.Paragraph("--"))
        assertThat(MarkdownParser.parse("- x").single()).isInstanceOf(MdBlock.Bullets::class.java)
    }
}
