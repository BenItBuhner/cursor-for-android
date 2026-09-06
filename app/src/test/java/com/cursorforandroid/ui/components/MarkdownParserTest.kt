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
        assertThat(bullets.ordered).isFalse()
        assertThat(bullets.items).containsExactly("one", "two continued").inOrder()
        val ordered = blocks[3] as MdBlock.Bullets
        assertThat(ordered.ordered).isTrue()
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
}
