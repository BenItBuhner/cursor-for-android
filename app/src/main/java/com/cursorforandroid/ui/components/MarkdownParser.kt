package com.cursorforandroid.ui.components

import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.MediaSegment

/** Block-level markdown structure: the CommonMark blocks agent replies use, plus GFM tables and task items. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    /**
     * A list. [start] is the number of the first item — `3.` starts a list at 3, and a numbered list that picks up
     * again after an unindented code block keeps counting — so the renderer never renumbers what the agent wrote.
     */
    data class Bullets(val items: List<ListItem>, val ordered: Boolean, val start: Int = 1) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    /** `> …`: whole blocks, so a quoted list or heading keeps its structure. */
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
    /** `<img src alt>` or `![alt](src)`; [src] is still the raw reference (artifact path, URL, data URI). */
    data class Image(val src: String, val alt: String?) : MdBlock
    /** `<video src poster>` (or a nested `<source src>`). */
    data class Video(val src: String, val poster: String?) : MdBlock
    /** A GFM pipe table. Every row has exactly `header.size` cells; [alignments] has one entry per column. */
    data class Table(val header: List<String>, val alignments: List<TableAlign>, val rows: List<List<String>>) : MdBlock
}

/**
 * One item of a list: its blocks (the first paragraph sits beside the marker, anything else — a nested list, a code
 * block — is indented under it) and, for a `- [ ]` / `- [x]` task item, whether it is checked.
 */
data class ListItem(val blocks: List<MdBlock>, val checked: Boolean? = null) {
    constructor(text: String, checked: Boolean? = null) : this(listOf(MdBlock.Paragraph(text)), checked)
}

enum class TableAlign { Start, Center, End }

/**
 * Turns a reply into blocks. CommonMark's container model — list items and quotes hold blocks of their own — with the
 * leniencies that suit model-written markdown: `---` under a line of text is a rule, not a setext heading; a nested
 * list indented two spaces under a numbered item still nests; a table ends at the first line without a pipe rather
 * than swallowing the sentence after it.
 */
object MarkdownParser {
    private val atxHeading = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val setextUnderline = Regex("""^ {0,3}=+[ \t]*$""")
    private val thematicBreak = Regex("""^ {0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val fenceOpen = Regex("""^( {0,3})(`{3,}|~{3,})(.*)$""")
    private val delimiterCell = Regex("""^:?-+:?$""")
    private val taskPrefix = Regex("""^\[([ xX])\](?:[ \t]+|$)""")
    /** A paragraph that is nothing but the HTML wrappers agents put around a section (`<details>`, `<div>`, `<p>`). */
    private val htmlWrapperOnly = Regex("""^(?:\s*</?(?:details|summary|div|p|center|br)\b[^>]*/?>\s*)+$""", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): List<MdBlock> = parseBlocks(markdown.replace("\r\n", "\n").replace('\r', '\n').lines())

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        // The last line appended to the paragraph ended with two spaces or a backslash: a hard line break follows it.
        var hardBreak = false

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            hardBreak = false
            if (text.isEmpty() || htmlWrapperOnly.matches(text)) return
            // Media tags sit in running text; each becomes its own block so it can be laid out as a figure.
            MediaMarkup.split(text).forEach { segment ->
                blocks += when (segment) {
                    is MediaSegment.Text -> MdBlock.Paragraph(segment.text)
                    is MediaSegment.Image -> MdBlock.Image(segment.src, segment.alt)
                    is MediaSegment.Video -> MdBlock.Video(segment.src, segment.poster)
                }
            }
        }

        fun appendParagraphLine(raw: String) {
            var content = raw.trim()
            val breakAfter = raw.endsWith("  ") || (content.endsWith("\\") && !content.endsWith("\\\\"))
            if (breakAfter && content.endsWith("\\")) content = content.dropLast(1).trimEnd()
            if (paragraph.isNotEmpty()) paragraph.append(if (hardBreak) '\n' else ' ')
            paragraph.append(content)
            hardBreak = breakAfter
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val inParagraph = paragraph.isNotEmpty()
            val fence = fenceAt(line)
            val heading = atxHeading.matchEntire(line)
            val marker = listMarker(line)
            when {
                line.isBlank() -> {
                    flushParagraph()
                    i++
                }
                fence != null -> {
                    flushParagraph()
                    i = parseFence(lines, i, fence, blocks)
                }
                heading != null -> {
                    flushParagraph()
                    blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                    i++
                }
                inParagraph && setextUnderline.matches(line) -> {
                    blocks += MdBlock.Heading(1, paragraph.toString().trim())
                    paragraph.setLength(0)
                    hardBreak = false
                    i++
                }
                thematicBreak.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                    i++
                }
                isQuote(line) -> {
                    flushParagraph()
                    i = parseQuote(lines, i, blocks)
                }
                marker != null && (!inParagraph || marker.canInterruptParagraph) -> {
                    flushParagraph()
                    i = parseList(lines, i, marker, blocks)
                }
                else -> {
                    val table = tableAt(lines, i)
                    if (table != null) {
                        flushParagraph()
                        blocks += table.first
                        i = table.second
                    } else {
                        appendParagraphLine(line)
                        i++
                    }
                }
            }
        }
        flushParagraph()
        return blocks
    }

    // --- Fenced code ---------------------------------------------------------------------------------------------

    /** The opening fence on [line], if it is one: three or more backticks or tildes; a backtick fence's info string cannot hold a backtick. */
    private fun fenceAt(line: String): MatchResult? =
        fenceOpen.matchEntire(line)?.takeIf { it.groupValues[2][0] == '~' || '`' !in it.groupValues[3] }

    private fun parseFence(lines: List<String>, start: Int, open: MatchResult, blocks: MutableList<MdBlock>): Int {
        val indent = open.groupValues[1].length
        val fence = open.groupValues[2]
        val language = open.groupValues[3].trim().split(' ', '\t').first().ifEmpty { null }
        val code = StringBuilder()
        var i = start + 1
        while (i < lines.size && !closesFence(lines[i], fence)) {
            // Content is indented relative to the fence, not the margin: an indented fence keeps its code flush.
            code.append(stripIndent(lines[i], minOf(indent, indentOf(lines[i])))).append('\n')
            i++
        }
        if (i < lines.size) i++ // the closing fence
        blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
        return i
    }

    /** A closing fence is the opening fence's character, at least as many times, and nothing else on the line. */
    private fun closesFence(line: String, fence: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence[0] }
    }

    /** Follows fence lines as they are appended to a container, so its lazy-continuation rules stay out of code. */
    private class FenceTracker {
        var open: String? = null
            private set

        fun track(line: String) {
            val fence = open
            if (fence == null) fenceOpen.matchEntire(line)?.let { open = it.groupValues[2] }
            else if (closesFence(line, fence)) open = null
        }
    }

    // --- Block quotes --------------------------------------------------------------------------------------------

    private fun isQuote(line: String): Boolean = line.trimStart().startsWith(">")

    private fun parseQuote(lines: List<String>, start: Int, blocks: MutableList<MdBlock>): Int {
        val inner = mutableListOf<String>()
        val fences = FenceTracker()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val content = when {
                trimmed.startsWith(">") -> trimmed.substring(1).let { if (it.startsWith(" ") || it.startsWith("\t")) it.substring(1) else it }
                // Lazy continuation: an unmarked line of prose right after a quoted one is still the quote's paragraph.
                line.isNotBlank() && fences.open == null && inner.isNotEmpty() && inner.last().isNotBlank() && !startsBlock(line) && tableAt(lines, i) == null -> line.trim()
                else -> break
            }
            inner += content
            fences.track(content)
            i++
        }
        blocks += MdBlock.Quote(parseBlocks(inner))
        return i
    }

    // --- Lists -----------------------------------------------------------------------------------------------------

    private class ListMarker(
        val indent: Int,
        val ordered: Boolean,
        val number: Int,
        /** Column the item's content starts at; lines indented this far belong to the item. */
        val contentIndent: Int,
        /** Index in the line where the first line's content begins. */
        val contentStart: Int,
        val emptyLine: Boolean,
    ) {
        /** CommonMark: a bullet interrupts a paragraph, a number only when it is 1 (so "2019. A year" stays prose). */
        val canInterruptParagraph: Boolean get() = !emptyLine && (!ordered || number == 1)
    }

    private fun listMarker(line: String): ListMarker? {
        val indent = indentOf(line)
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i >= line.length) return null
        val markerStart = i
        var ordered = false
        var number = 1
        when {
            line[i] == '-' || line[i] == '*' || line[i] == '+' -> i++
            line[i].isDigit() -> {
                var j = i
                while (j < line.length && line[j].isDigit() && j - i < 9) j++
                if (j >= line.length || (line[j] != '.' && line[j] != ')')) return null
                ordered = true
                number = line.substring(i, j).toInt()
                i = j + 1
            }
            else -> return null
        }
        val markerWidth = i - markerStart
        if (i >= line.length) return ListMarker(indent, ordered, number, indent + markerWidth + 1, i, emptyLine = true)
        if (line[i] != ' ' && line[i] != '\t') return null
        var k = i
        while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
        if (k >= line.length) return ListMarker(indent, ordered, number, indent + markerWidth + 1, k, emptyLine = true)
        val gap = k - i
        // Five or more spaces after the marker mean the content is indented code; it starts one space in.
        return if (gap <= 4) ListMarker(indent, ordered, number, indent + markerWidth + gap, k, emptyLine = false)
        else ListMarker(indent, ordered, number, indent + markerWidth + 1, i + 1, emptyLine = false)
    }

    private fun parseList(lines: List<String>, start: Int, first: ListMarker, blocks: MutableList<MdBlock>): Int {
        val items = mutableListOf<ListItem>()
        var i = start
        var next: ListMarker? = first
        while (true) {
            val marker = next ?: break
            val itemLines = mutableListOf<String>()
            val fences = FenceTracker()
            fun add(content: String) {
                itemLines += content
                fences.track(content)
            }
            add(if (marker.emptyLine) "" else lines[i].substring(marker.contentStart))
            // Lines indented to the content column belong to the item. Two spaces under a numbered item (whose content
            // column is three in) count too: that is how nested bullets are usually typed under `1.`.
            val nestedIndent = minOf(marker.contentIndent, marker.indent + 2)
            var j = i + 1
            var blanks = 0
            while (j < lines.size) {
                val line = lines[j]
                if (line.isBlank()) {
                    blanks++
                    j++
                    continue
                }
                val indent = indentOf(line)
                if (indent >= nestedIndent) {
                    repeat(blanks) { add("") }
                    blanks = 0
                    add(stripIndent(line, minOf(indent, marker.contentIndent)))
                    j++
                    continue
                }
                // Unindented after a blank line: the item is over. Right after prose: lazy continuation of that prose.
                if (blanks > 0) break
                val lazy = fences.open == null && itemLines.last().isNotBlank() && !startsBlock(line) && tableAt(lines, j) == null
                if (!lazy) break
                add(line.trim())
                j++
            }
            items += listItem(itemLines)
            i = j
            val sibling = if (i < lines.size) listMarker(lines[i])?.takeIf { it.ordered == first.ordered } else null
            // A numbered list that starts over at 1 after a blank line is a new list, not this one's next item.
            next = sibling?.takeIf { !(blanks > 0 && it.ordered && it.number == 1 && first.number + items.size != 1) }
        }
        blocks += MdBlock.Bullets(items, first.ordered, if (first.ordered) first.number else 1)
        return i
    }

    private fun listItem(lines: List<String>): ListItem {
        val content = lines.toMutableList()
        var checked: Boolean? = null
        taskPrefix.find(content[0])?.let { task ->
            checked = task.groupValues[1] != " "
            content[0] = content[0].substring(task.range.last + 1)
        }
        return ListItem(parseBlocks(content), checked)
    }

    // --- Tables ----------------------------------------------------------------------------------------------------

    /** The table whose header row is [lines] at [i], with the index after its last row; null when the next line is not a delimiter row. */
    private fun tableAt(lines: List<String>, i: Int): Pair<MdBlock.Table, Int>? {
        if (i + 1 >= lines.size) return null
        val headerLine = lines[i]
        val delimiterLine = lines[i + 1]
        if ('|' !in headerLine || '|' !in delimiterLine) return null
        val delimiters = splitTableRow(delimiterLine)
        if (delimiters.isEmpty() || !delimiters.all(delimiterCell::matches)) return null
        val header = splitTableRow(headerLine)
        if (header.size != delimiters.size) return null
        val alignments = delimiters.map {
            when {
                it.startsWith(":") && it.endsWith(":") -> TableAlign.Center
                it.endsWith(":") -> TableAlign.End
                else -> TableAlign.Start
            }
        }
        val rows = mutableListOf<List<String>>()
        var j = i + 2
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank() || '|' !in line || startsBlock(line)) break
            val cells = splitTableRow(line)
            // GFM: short rows are padded with empty cells, long rows lose the excess.
            rows += List(header.size) { cells.getOrElse(it) { "" } }
            j++
        }
        return MdBlock.Table(header, alignments, rows) to j
    }

    /** Cells of a pipe-table row: optional outer pipes, `\|` for a literal pipe, other escapes left for the inline pass. */
    internal fun splitTableRow(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < row.length) {
            val c = row[k]
            when {
                c == '\\' && k + 1 < row.length && row[k + 1] == '|' -> {
                    cell.append('|')
                    k += 2
                }
                c == '\\' && k + 1 < row.length -> {
                    cell.append(c).append(row[k + 1])
                    k += 2
                }
                c == '|' -> {
                    cells += cell.toString().trim()
                    cell.setLength(0)
                    k++
                }
                else -> {
                    cell.append(c)
                    k++
                }
            }
        }
        cells += cell.toString().trim()
        return cells
    }

    // --- Shared ----------------------------------------------------------------------------------------------------

    /** Whether [line] opens a block of its own, which ends any lazy paragraph continuation. */
    private fun startsBlock(line: String): Boolean =
        fenceAt(line) != null || atxHeading.matches(line) || thematicBreak.matches(line) || isQuote(line) || listMarker(line) != null

    /** Leading indentation in columns: a tab advances to the next multiple of four. */
    private fun indentOf(line: String): Int {
        var column = 0
        for (c in line) {
            when (c) {
                ' ' -> column++
                '\t' -> column += 4 - column % 4
                else -> return column
            }
        }
        return column
    }

    /** [line] without its first [columns] columns of indentation; a tab straddling the cut leaves its remainder as spaces. */
    private fun stripIndent(line: String, columns: Int): String {
        if (columns <= 0) return line
        var column = 0
        var i = 0
        while (i < line.length && column < columns) {
            when (line[i]) {
                ' ' -> column++
                '\t' -> {
                    val width = 4 - column % 4
                    if (column + width > columns) return " ".repeat(column + width - columns) + line.substring(i + 1)
                    column += width
                }
                else -> return line.substring(i)
            }
            i++
        }
        return line.substring(i)
    }
}
