package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
        assertThat(bullets.items).containsExactly("Before: <img src=\"a.png\">", "After: <img src=\"b.png\">").inOrder()
    }
}

class InlineMarkdownTest {

    private fun render(text: String) = InlineMarkdown.render(
        text = text,
        base = TextStyle(fontSize = 14.sp),
        codeColor = Color.Black,
        codeBackground = Color.LightGray,
        linkColor = Color.Blue,
        boldColor = Color.Black,
    )

    private fun urlsIn(text: String): List<String> {
        val rendered = render(text)
        return rendered.getLinkAnnotations(0, rendered.length).map { it.item }.filterIsInstance<LinkAnnotation.Url>().map { it.url }
    }

    @Test
    fun `a PR link with a hash in the label becomes the label and a url, not raw markup`() {
        // The shape an agent writes after opening a pull request — the bug in the conversation screenshot.
        val md = "Opened [PR #66](https://github.com/BenItBuhner/cursor-for-android/pull/66) against `main`."
        val rendered = render(md)
        assertThat(rendered.text).isEqualTo("Opened PR #66 against  main .")
        assertThat(rendered.text).doesNotContain("](")
        val links = rendered.getLinkAnnotations(0, rendered.length).map { it.item }.filterIsInstance<LinkAnnotation.Url>()
        assertThat(links.map { it.url }).containsExactly("https://github.com/BenItBuhner/cursor-for-android/pull/66")
    }

    @Test
    fun `bold wrapping a link still renders the link, not the markdown`() {
        val rendered = render("**[PR #66](https://github.com/o/r/pull/66)** against `main`")
        assertThat(rendered.text).doesNotContain("](")
        assertThat(rendered.text).contains("PR #66")
        assertThat(urlsIn("**[PR #66](https://github.com/o/r/pull/66)**")).containsExactly("https://github.com/o/r/pull/66")
    }

    @Test
    fun `italic wrapping a link still renders the link`() {
        assertThat(urlsIn("See *[the docs](https://cursor.com)* for more.")).containsExactly("https://cursor.com")
        assertThat(render("See *[the docs](https://cursor.com)* for more.").text).isEqualTo("See the docs for more.")
    }

    @Test
    fun `link labels keep their own bold and code`() {
        val rendered = render("[**PR** `#66`](https://example.com/x)")
        assertThat(rendered.text).isEqualTo("PR  #66 ")
        assertThat(urlsIn("[**PR** `#66`](https://example.com/x)")).containsExactly("https://example.com/x")
    }

    @Test
    fun `titles, angle brackets and a space before the destination still parse`() {
        assertThat(InlineMarkdown.parseMarkdownLink("[x](https://a.test \"Hi\")", 0)?.url).isEqualTo("https://a.test")
        assertThat(InlineMarkdown.parseMarkdownLink("[x](<https://a.test>)", 0)?.url).isEqualTo("https://a.test")
        assertThat(InlineMarkdown.parseMarkdownLink("[x] (https://a.test)", 0)?.url).isEqualTo("https://a.test")
    }

    @Test
    fun `autolinks, bare urls and html anchors become links`() {
        assertThat(urlsIn("See <https://cursor.com/docs>.")).containsExactly("https://cursor.com/docs")
        assertThat(urlsIn("See https://cursor.com/docs.")).containsExactly("https://cursor.com/docs")
        assertThat(render("See https://cursor.com/docs.").text).isEqualTo("See https://cursor.com/docs.")
        assertThat(urlsIn("<a href=\"https://x.test/p\">PR #1</a>")).containsExactly("https://x.test/p")
        assertThat(render("<a href=\"https://x.test/p\">PR #1</a>").text).isEqualTo("PR #1")
    }

    @Test
    fun `strikethrough wraps nested markup`() {
        val rendered = render("~~old [link](https://x.test)~~")
        assertThat(rendered.text).isEqualTo("old link")
        assertThat(urlsIn("~~old [link](https://x.test)~~")).containsExactly("https://x.test")
    }
}
