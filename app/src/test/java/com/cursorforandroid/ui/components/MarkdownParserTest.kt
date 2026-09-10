package com.cursorforandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {

    private fun paragraph(text: String) = MdBlock.Paragraph(text)
    private fun item(text: String) = ListItem(text)
    private fun bullets(vararg items: String) = MdBlock.Bullets(items.map(::item), ordered = false)
    private fun numbered(start: Int, vararg items: String) = MdBlock.Bullets(items.map(::item), ordered = true, start = start)

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
        assertThat(blocks[2]).isEqualTo(bullets("one", "two continued"))
        assertThat(blocks[3]).isEqualTo(numbered(1, "first", "second"))
        val code = blocks[4] as MdBlock.Code
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("val x = 1")
        assertThat(blocks[5]).isEqualTo(MdBlock.Quote(listOf(paragraph("quoted line continues"))))
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
    fun `media inside a list item becomes a block of that item`() {
        val blocks = MarkdownParser.parse("- Before: <img src=\"a.png\">\n- After: <img src=\"b.png\">")
        val list = blocks.single() as MdBlock.Bullets
        assertThat(list.items).containsExactly(
            ListItem(listOf(paragraph("Before:"), MdBlock.Image("a.png", null))),
            ListItem(listOf(paragraph("After:"), MdBlock.Image("b.png", null))),
        ).inOrder()
    }

    // --- Tables ------------------------------------------------------------------------------------------------------

    @Test
    fun `a pipe table becomes a table block instead of a paragraph of pipes`() {
        // The table from the crash-analysis reply that rendered as "| Minute | Buy VWAP | Event | |---|---|---| | 12:15 …".
        val md = """
            Stage 5 — The crash

            | Minute | Buy VWAP | Event |
            |---|---|---|
            | 12:15 | ${'$'}159 | `0x4b3f…` (MM-3's seller, 810k issuer tokens) starts selling |
            | 12:19 | ${'$'}108 | **${'$'}492k sell wave** — first big sell > buy minute |
            | now | ${'$'}0.87 | -99.7% from peak VWAP |

            First hour totals: **${'$'}10.71M bought** by 22,378 wallets.
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Paragraph", "Table", "Paragraph").inOrder()
        val table = blocks[1] as MdBlock.Table
        assertThat(table.header).containsExactly("Minute", "Buy VWAP", "Event").inOrder()
        assertThat(table.alignments).containsExactly(TableAlign.Start, TableAlign.Start, TableAlign.Start)
        assertThat(table.rows).hasSize(3)
        assertThat(table.rows[0]).containsExactly("12:15", "\$159", "`0x4b3f…` (MM-3's seller, 810k issuer tokens) starts selling").inOrder()
        assertThat(table.rows[1][2]).isEqualTo("**\$492k sell wave** — first big sell > buy minute")
        assertThat((blocks[2] as MdBlock.Paragraph).text).startsWith("First hour totals")
    }

    @Test
    fun `table cells honour alignment, escaped pipes, missing outer pipes and uneven rows`() {
        val md = """
            Wallet | Source | USD realized
            :--- | :---: | ---:
            `0x4b3f…1f2a` | MM-3 \| SAFE_D | 2,407,993
            0x11c1… | 500,050 from MM-2
            a | b | c | dropped
        """.trimIndent()
        val table = MarkdownParser.parse(md).single() as MdBlock.Table
        assertThat(table.header).containsExactly("Wallet", "Source", "USD realized").inOrder()
        assertThat(table.alignments).containsExactly(TableAlign.Start, TableAlign.Center, TableAlign.End).inOrder()
        assertThat(table.rows).containsExactly(
            listOf("`0x4b3f…1f2a`", "MM-3 | SAFE_D", "2,407,993"),
            listOf("0x11c1…", "500,050 from MM-2", ""),
            listOf("a", "b", "c"),
        ).inOrder()
    }

    @Test
    fun `a table may follow a paragraph directly and ends at a blank line or a line without pipes`() {
        val blocks = MarkdownParser.parse("Who got the money\n| a | b |\n|---|---|\n| 1 | 2 |\nTrailing sentence.\n| 3 | 4 |")
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Paragraph", "Table", "Paragraph").inOrder()
        assertThat((blocks[0] as MdBlock.Paragraph).text).isEqualTo("Who got the money")
        assertThat((blocks[1] as MdBlock.Table).rows).containsExactly(listOf("1", "2"))
        // The stray row with no delimiter row under it is just text.
        assertThat((blocks[2] as MdBlock.Paragraph).text).isEqualTo("Trailing sentence. | 3 | 4 |")
    }

    @Test
    fun `a header whose cell count differs from the delimiter row is not a table`() {
        val blocks = MarkdownParser.parse("| a | b | c |\n|---|---|")
        assertThat(blocks.single()).isInstanceOf(MdBlock.Paragraph::class.java)
        assertThat(MarkdownParser.parse("| just | text |").single()).isEqualTo(paragraph("| just | text |"))
    }

    @Test
    fun `a single column table and a table with a heading right after it`() {
        val blocks = MarkdownParser.parse("| Only |\n| - |\n| x |\n## Next")
        assertThat(blocks).containsExactly(
            MdBlock.Table(listOf("Only"), listOf(TableAlign.Start), listOf(listOf("x"))),
            MdBlock.Heading(2, "Next"),
        ).inOrder()
    }

    // --- Lists -------------------------------------------------------------------------------------------------------

    @Test
    fun `nested lists keep their structure`() {
        val md = """
            - Parent
              - Child one
              - Child two
                1. Grandchild
            - Sibling
        """.trimIndent()
        val list = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(list.items).hasSize(2)
        val parent = list.items[0]
        assertThat(parent.blocks[0]).isEqualTo(paragraph("Parent"))
        val children = parent.blocks[1] as MdBlock.Bullets
        assertThat(children.ordered).isFalse()
        assertThat(children.items[0]).isEqualTo(item("Child one"))
        assertThat(children.items[1].blocks).containsExactly(paragraph("Child two"), numbered(1, "Grandchild")).inOrder()
        assertThat(list.items[1]).isEqualTo(item("Sibling"))
    }

    @Test
    fun `bullets indented two spaces under a numbered item nest, as they are usually typed`() {
        val list = MarkdownParser.parse("1. Step\n  - detail\n2. Next").single() as MdBlock.Bullets
        assertThat(list.ordered).isTrue()
        assertThat(list.items[0].blocks).containsExactly(paragraph("Step"), bullets("detail")).inOrder()
        assertThat(list.items[1]).isEqualTo(item("Next"))
    }

    @Test
    fun `an ordered list keeps its start number and continues after an unindented code block`() {
        val md = """
            3. third
            4. fourth

            1. Install:
            ```bash
            npm install
            ```
            2. Run it
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertThat(blocks.map { it::class.simpleName }).containsExactly("Bullets", "Bullets", "Code", "Bullets").inOrder()
        assertThat(blocks[0]).isEqualTo(numbered(3, "third", "fourth"))
        assertThat(blocks[1]).isEqualTo(numbered(1, "Install:"))
        assertThat(blocks[3]).isEqualTo(numbered(2, "Run it"))
    }

    @Test
    fun `a code block indented under a list item belongs to the item`() {
        val md = """
            1. Install:

               ```bash
               npm install
               ```

            2. Run it
        """.trimIndent()
        val list = MarkdownParser.parse(md).single() as MdBlock.Bullets
        assertThat(list.items).hasSize(2)
        assertThat(list.items[0].blocks).containsExactly(paragraph("Install:"), MdBlock.Code("bash", "npm install")).inOrder()
        assertThat(list.items[1]).isEqualTo(item("Run it"))
    }

    @Test
    fun `task items carry their checkbox and lose the brackets`() {
        val list = MarkdownParser.parse("- [x] done\n- [ ] open\n- [x]\n- [not] a task").single() as MdBlock.Bullets
        assertThat(list.items).containsExactly(
            ListItem("done", checked = true),
            ListItem("open", checked = false),
            ListItem(emptyList(), checked = true),
            ListItem("[not] a task"),
        ).inOrder()
    }

    @Test
    fun `a numbered line other than 1 does not interrupt a paragraph, a bullet does`() {
        assertThat(MarkdownParser.parse("Released in\n2019. A good year").single()).isEqualTo(paragraph("Released in 2019. A good year"))
        assertThat(MarkdownParser.parse("Steps:\n1. one\n2. two")).containsExactly(paragraph("Steps:"), numbered(1, "one", "two")).inOrder()
        assertThat(MarkdownParser.parse("Items:\n- a\n* b")).containsExactly(paragraph("Items:"), bullets("a", "b")).inOrder()
    }

    @Test
    fun `an unindented line right after an item continues the item's paragraph`() {
        assertThat(MarkdownParser.parse("- first line\nwraps here\n- second")).containsExactly(bullets("first line wraps here", "second"))
    }

    // --- Other blocks ------------------------------------------------------------------------------------------------

    @Test
    fun `tilde fences, longer fences and fenced backticks inside them`() {
        val blocks = MarkdownParser.parse("~~~python\nprint(1)\n~~~\n````md\n```\ninner\n```\n````")
        assertThat(blocks).containsExactly(
            MdBlock.Code("python", "print(1)"),
            MdBlock.Code("md", "```\ninner\n```"),
        ).inOrder()
    }

    @Test
    fun `fence info strings keep only the language`() {
        assertThat((MarkdownParser.parse("```ts title=\"a.ts\"\nx\n```").single() as MdBlock.Code).language).isEqualTo("ts")
        assertThat((MarkdownParser.parse("```\nx\n```").single() as MdBlock.Code).language).isNull()
    }

    @Test
    fun `every spelling of a thematic break is a rule, a setext underline is a heading`() {
        assertThat(MarkdownParser.parse("---\n***\n___\n- - -\n-----\n* * *")).containsExactly(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule, MdBlock.Rule, MdBlock.Rule, MdBlock.Rule)
        assertThat(MarkdownParser.parse("Title\n=====")).containsExactly(MdBlock.Heading(1, "Title"))
        // `---` under text stays a rule: agents write it as a separator, never as an H2 underline.
        assertThat(MarkdownParser.parse("Closing line.\n---\n## Next")).containsExactly(paragraph("Closing line."), MdBlock.Rule, MdBlock.Heading(2, "Next")).inOrder()
    }

    @Test
    fun `headings drop closing hashes and need a space after the opening ones`() {
        assertThat(MarkdownParser.parse("## Title ##")).containsExactly(MdBlock.Heading(2, "Title"))
        assertThat(MarkdownParser.parse("# C#")).containsExactly(MdBlock.Heading(1, "C#"))
        assertThat(MarkdownParser.parse("#hashtag")).containsExactly(paragraph("#hashtag"))
        assertThat(MarkdownParser.parse("####### seven")).containsExactly(paragraph("####### seven"))
    }

    @Test
    fun `two trailing spaces or a backslash break the line inside a paragraph`() {
        assertThat(MarkdownParser.parse("first  \nsecond\\\nthird\nfourth")).containsExactly(paragraph("first\nsecond\nthird fourth"))
    }

    @Test
    fun `quotes hold blocks and continue lazily`() {
        val md = """
            > **Note**
            > - one
            > - two
            still quoted
            
            not quoted
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertThat(blocks).containsExactly(
            MdBlock.Quote(listOf(paragraph("**Note**"), MdBlock.Bullets(listOf(item("one"), item("two still quoted")), ordered = false))),
            paragraph("not quoted"),
        ).inOrder()
    }

    @Test
    fun `html wrappers with nothing inside them are dropped`() {
        val md = """
            <details>
            <summary>Full output</summary>

            body text

            </details>
        """.trimIndent()
        // The opening wrapper stays in the first paragraph's text; the inline renderer drops the tag itself.
        assertThat(MarkdownParser.parse(md)).containsExactly(paragraph("<details> <summary>Full output</summary>"), paragraph("body text")).inOrder()
    }

    @Test
    fun `windows line endings and tabs are handled`() {
        assertThat(MarkdownParser.parse("a\r\n\r\nb")).containsExactly(paragraph("a"), paragraph("b")).inOrder()
        val list = MarkdownParser.parse("- a\n\t- nested").single() as MdBlock.Bullets
        assertThat(list.items.single().blocks).containsExactly(paragraph("a"), bullets("nested")).inOrder()
    }

    @Test
    fun `split table row`() {
        assertThat(MarkdownParser.splitTableRow("| a | b |")).containsExactly("a", "b").inOrder()
        assertThat(MarkdownParser.splitTableRow("a | b")).containsExactly("a", "b").inOrder()
        assertThat(MarkdownParser.splitTableRow("| a \\| b | `c` |")).containsExactly("a | b", "`c`").inOrder()
        assertThat(MarkdownParser.splitTableRow("|")).containsExactly("")
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
