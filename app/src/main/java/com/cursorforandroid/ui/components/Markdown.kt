package com.cursorforandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.MediaSegment
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.JetBrainsMono

/** Block-level markdown structure. Deliberately small: what agent replies actually use. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullets(val items: List<MdItem>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    /** `<img src alt>` or `![alt](src)`; [src] is still the raw reference (artifact path, URL, data URI). */
    data class Image(val src: String, val alt: String?) : MdBlock
    /** `<video src poster>` (or a nested `<source src>`). */
    data class Video(val src: String, val poster: String?) : MdBlock
}

/**
 * One line of a list. [depth] is how far it is nested (0 for the outer level) and [number] is the numeral to print,
 * or null for a bullet.
 */
data class MdItem(val text: String, val depth: Int = 0, val number: Int? = null)

object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^([ \\t]*)[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^([ \\t]*)(\\d+)[.)]\\s+(.*)$")

    /** Three or more of the same marker, spaces allowed between them: `---`, `----`, `***`, `___`, `- - -`. */
    private val ruleRegex = Regex("^ {0,3}([-*_])[ \\t]*(?:\\1[ \\t]*){2,}$")

    private fun isItem(line: String) = bulletRegex.matches(line) || orderedRegex.matches(line)

    /** Leading whitespace as a column, so a tab-indented sublist nests like a space-indented one. */
    private fun indentOf(whitespace: String): Int = whitespace.sumOf { if (it == '\t') 4 else 1 }

    fun parse(markdown: String): List<MdBlock> = parseWithStarts(markdown).blocks

    /** Blocks plus, for each, the offset of the line its construct started on; see [IncrementalMarkdown]. */
    class Parsed internal constructor(val blocks: List<MdBlock>, val starts: IntArray)

    fun parseWithStarts(markdown: String): Parsed {
        val lines = markdown.lines()
        val lineStart = IntArray(lines.size)
        var at = 0
        for (index in lines.indices) {
            lineStart[index] = at
            at += lines[index].length
            if (at < markdown.length) at += if (markdown.startsWith("\r\n", at)) 2 else 1
        }
        val blocks = mutableListOf<MdBlock>()
        val starts = mutableListOf<Int>()
        val paragraph = StringBuilder()
        var paragraphStart = 0
        fun emit(block: MdBlock, start: Int) {
            blocks += block
            starts += start
        }
        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                // Media tags sit in running text; each becomes its own block so it can be laid out as a figure.
                MediaMarkup.split(paragraph.toString().trim()).forEach { segment ->
                    emit(
                        when (segment) {
                            is MediaSegment.Text -> MdBlock.Paragraph(segment.text)
                            is MediaSegment.Image -> MdBlock.Image(segment.src, segment.alt)
                            is MediaSegment.Video -> MdBlock.Video(segment.src, segment.poster)
                        },
                        paragraphStart,
                    )
                }
            }
            paragraph.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flushParagraph()
                    val start = lineStart[i]
                    val lang = line.trim().removePrefix("```").trim().ifEmpty { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    emit(MdBlock.Code(lang, code.toString().trimEnd('\n')), start)
                }
                line.isBlank() -> flushParagraph()
                headingRegex.matches(line) -> {
                    flushParagraph()
                    val m = headingRegex.find(line)!!
                    emit(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()), lineStart[i])
                }
                ruleRegex.matches(line) -> {
                    flushParagraph()
                    emit(MdBlock.Rule, lineStart[i])
                }
                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    val start = lineStart[i]
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    emit(MdBlock.Quote(quote.toString().trim()), start)
                    continue
                }
                isItem(line) -> {
                    flushParagraph()
                    val start = lineStart[i]
                    val items = mutableListOf<MdItem>()
                    // One entry per open level: the column its items start at, the numeral the next ordered item
                    // there prints, and whether that level is currently an ordered list. A sublist keeps the
                    // numbers its author wrote instead of restarting the outer count, and a level resumed after a
                    // sublist carries on from where it left off.
                    val columns = mutableListOf<Int>()
                    val counters = mutableListOf<Int>()
                    val ordereds = mutableListOf<Boolean>()
                    var outerOrdered: Boolean? = null
                    while (i < lines.size && isItem(lines[i]) && !ruleRegex.matches(lines[i])) {
                        val ordered = orderedRegex.find(lines[i])
                        val bullet = ordered ?: bulletRegex.find(lines[i])!!
                        val column = indentOf(bullet.groupValues[1])
                        val first = if (ordered != null) ordered.groupValues[2].toIntOrNull() ?: 1 else 1
                        // Swapping the marker at the outer level starts a new list, as it does in CommonMark: a
                        // bullet list followed by "1." is two lists, not one with mixed markers.
                        if (outerOrdered != null && column <= columns.first() && (ordered != null) != outerOrdered) break
                        if (outerOrdered == null) outerOrdered = ordered != null

                        while (columns.size > 1 && column < columns.last()) {
                            columns.removeAt(columns.lastIndex)
                            counters.removeAt(counters.lastIndex)
                            ordereds.removeAt(ordereds.lastIndex)
                        }
                        if (columns.isEmpty() || column > columns.last()) {
                            columns += column
                            counters += first
                            ordereds += ordered != null
                        } else if (ordereds.last() != (ordered != null)) {
                            // Swapping the marker at a nested level starts a new list there too: it numbers from
                            // what the author wrote, and a bullet does not advance the count above it.
                            counters[counters.lastIndex] = first
                            ordereds[ordereds.lastIndex] = ordered != null
                        } else {
                            counters[counters.lastIndex]++
                        }
                        val depth = columns.lastIndex

                        val text = StringBuilder(bullet.groupValues.last())
                        i++
                        // Wrapped text belongs to the item above it; a line that starts its own marker does not.
                        while (i < lines.size && lines[i].isNotBlank() && !isItem(lines[i]) &&
                            !ruleRegex.matches(lines[i]) && lines[i].takeWhile { it.isWhitespace() }.let(::indentOf) >= column + 2
                        ) {
                            text.append(' ').append(lines[i].trim())
                            i++
                        }
                        items += MdItem(text.toString(), depth, if (ordered != null) counters[depth] else null)
                    }
                    emit(MdBlock.Bullets(items), start)
                    continue
                }
                else -> {
                    if (paragraph.isEmpty()) paragraphStart = lineStart[i] else paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushParagraph()
        return Parsed(blocks, starts.toIntArray())
    }
}

/**
 * The parse of a message that is still being written. A streamed reply only ever gains text at the end, so every
 * block above the one being written is final: this keeps those and re-reads only the open tail, instead of parsing
 * the whole reply from character zero on every delta.
 *
 * Not thread safe; one instance belongs to one [MarkdownText].
 */
class IncrementalMarkdown {
    private var source: String? = null
    private var blocks: List<MdBlock> = emptyList()
    private var settled: List<MdBlock> = emptyList()
    /**
     * The prefix of the source [settled] was parsed from. It stops one block short of the open one: the line still
     * being written can join the block above it (a `-` becoming `- item`, a `#` becoming a heading), so the last
     * two blocks are always read again.
     */
    private var settledText: String = ""

    fun parse(markdown: String): List<MdBlock> {
        val previous = source
        if (previous != null && (previous === markdown || previous == markdown)) return blocks
        if (!markdown.startsWith(settledText)) {
            settled = emptyList()
            settledText = ""
        }
        val tail = if (settledText.isEmpty()) markdown else markdown.substring(settledText.length)
        val parsed = MarkdownParser.parseWithStarts(tail)
        val all = if (settled.isEmpty()) parsed.blocks else settled + parsed.blocks
        val open = parsed.starts.lastOrNull() ?: 0
        val openFrom = parsed.starts.lastOrNull { it < open } ?: 0
        if (openFrom > 0) {
            val complete = parsed.starts.count { it < openFrom }
            settled = all.subList(0, settled.size + complete).toList()
            settledText = markdown.substring(0, settledText.length + openFrom)
        }
        source = markdown
        blocks = all
        return all
    }
}

/** How many [delimiter]s in a row start at [from]. */
private fun runLength(text: String, from: Int, delimiter: Char): Int {
    var end = from
    while (end < text.length && text[end] == delimiter) end++
    return end - from
}

/** Where the next run of exactly [length] [delimiter]s starts at or after [from], or -1. */
private fun closingRun(text: String, from: Int, delimiter: Char, length: Int): Int {
    var at = text.indexOf(delimiter, from)
    while (at >= 0) {
        val run = runLength(text, at, delimiter)
        if (run == length) return at
        at = text.indexOf(delimiter, at + run)
    }
    return -1
}

/** True when the delimiter at [at] hugs the text after it, and for `_` is not sitting inside a word. */
private fun opensEmphasis(text: String, at: Int): Boolean {
    val c = text[at]
    if (c != '*' && c != '_') return false
    // A doubled delimiter is bold (handled before this) or, for `__init__`, a name; either way not an italic run.
    if (at + 1 >= text.length || text[at + 1].isWhitespace() || text[at + 1] == c) return false
    if (at > 0 && text[at - 1] == c) return false
    return c == '*' || at == 0 || !text[at - 1].isLetterOrDigit()
}

/** Where the emphasis opened with [delimiter] closes: the next one hugging the text before it, or -1. */
private fun closingEmphasis(text: String, from: Int, delimiter: Char): Int {
    var at = text.indexOf(delimiter, from)
    while (at > 0) {
        val hugsText = !text[at - 1].isWhitespace()
        val outsideWord = delimiter == '*' || at + 1 >= text.length || !text[at + 1].isLetterOrDigit()
        if (hugsText && outsideWord) return at
        at = text.indexOf(delimiter, at + 1)
    }
    return -1
}

object InlineMarkdown {
    private val schemeRegex = Regex("[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val htmlAnchor = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val autolink = Regex("""<(https?://[^>\s]+)>""", RegexOption.IGNORE_CASE)
    private val bareUrl = Regex("""https?://[^\s<]+""", RegexOption.IGNORE_CASE)

    /** Schemes a phone reliably has a handler for. Everything else — `file:`, `javascript:`, a bare path — is text. */
    private val openableSchemes = setOf("http", "https", "mailto", "tel", "sms")

    /**
     * The address `[text](target)` should open, or null when the target is not something the system can be asked
     * for. Agents write repository-relative links constantly (`./diff.patch`, `app/src/main/…`), and a scheme-less
     * URI resolves to no activity at all: those stay plain text instead of becoming a link that fails on tap.
     */
    fun linkTarget(raw: String): String? {
        val target = raw.trim()
        // Protocol-relative, as written in copied HTML.
        if (target.startsWith("//")) return "https:$target"
        val scheme = schemeRegex.matchAt(target, 0) ?: return null
        if (scheme.value.dropLast(1).lowercase() !in openableSchemes) return null
        return target.takeIf { it.length > scheme.value.length }
    }

    /**
     * Opens a tapped link. `AndroidUriHandler` throws when no activity handles the address — a `mailto:` on a device
     * with no mail app — and the throw would come out of the click handler on the main thread, so it is swallowed.
     */
    fun opener(uriHandler: UriHandler): (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    /**
     * Renders inline code, bold, italics, strikethrough and links to an AnnotatedString. Nested markup is parsed
     * (a bold wrap around a `[label](url)` is still a link); inline code stays literal. Inline code gets a 12% base
     * chip in JetBrains Mono, links use Cursor's textLink blue, both matching the desktop chat renderer.
     *
     * [onLinkClick] receives the target of a tapped link. Without one the annotation falls back to Compose's
     * `LocalUriHandler`, which throws when nothing on the device handles the address.
     */
    fun render(
        text: String,
        base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
        onLinkClick: ((String) -> Unit)? = null,
    ): AnnotatedString = buildAnnotatedString {
        appendInline(text, base, codeColor, codeBackground, linkColor, boldColor, onLinkClick, insideLink = false)
    }

    private fun AnnotatedString.Builder.appendInline(
        text: String,
        base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
        onLinkClick: ((String) -> Unit)?,
        insideLink: Boolean,
    ) {
        var i = 0
        val n = text.length
        var noStarCloser = Int.MAX_VALUE
        var noUnderscoreCloser = Int.MAX_VALUE
        fun recurse(inner: String) = appendInline(inner, base, codeColor, codeBackground, linkColor, boldColor, onLinkClick, insideLink)
        fun label(inner: String) = appendInline(inner, base, codeColor, codeBackground, linkColor, boldColor, onLinkClick, insideLink = true)
        fun linkStyle() = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None))
        fun emitLink(url: String, text: String) {
            // Labels keep bold/code/italic, but not nested links — a bare-URL label would recurse forever. An address
            // nothing on the device can open is not made into a link at all (see [linkTarget]).
            val target = linkTarget(url)
            if (target == null) {
                label(text)
                return
            }
            withLink(
                LinkAnnotation.Url(
                    url = target,
                    styles = linkStyle(),
                    linkInteractionListener = onLinkClick?.let { click -> LinkInteractionListener { click(target) } },
                ),
            ) { label(text) }
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> {
                    // A run of backticks is closed by a run of the same length, which is how CommonMark lets
                    // ``a ` b`` show a backtick. Matching a single one would close on the opener's second tick and
                    // emit an empty chip followed by the literal content.
                    val open = runLength(text, i, '`')
                    val end = closingRun(text, i + open, '`', open)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = JetBrainsMono, color = codeColor, background = codeBackground, fontSize = base.fontSize * 0.9f)) {
                            append(" ${text.substring(i + open, end).trim()} ")
                        }
                        i = end + open
                    } else {
                        append(c); i++
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { recurse(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append("~~"); i += 2
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) { recurse(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append("**"); i += 2
                    }
                }
                opensEmphasis(text, i) -> {
                    // Only a delimiter hugging the text it emphasises counts, so "2 * 3 * 4" is arithmetic rather
                    // than an italic 3. A scan that runs off the end proves there is no closer later either.
                    val exhausted = if (c == '*') noStarCloser else noUnderscoreCloser
                    val end = if (i >= exhausted) -1 else closingEmphasis(text, i + 1, c)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { recurse(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        if (c == '*') noStarCloser = i else noUnderscoreCloser = i
                        append(c); i++
                    }
                }
                c == '[' && !insideLink -> {
                    val link = parseMarkdownLink(text, i)
                    if (link != null) {
                        emitLink(link.url, link.label)
                        i = link.end
                    } else {
                        append(c); i++
                    }
                }
                c == '<' && !insideLink -> {
                    val html = htmlAnchor.matchAt(text, i)
                    if (html != null) {
                        emitLink(html.groupValues[1].trim(), html.groupValues[2])
                        i = html.range.last + 1
                    } else {
                        val auto = autolink.matchAt(text, i)
                        if (auto != null) {
                            val url = auto.groupValues[1]
                            emitLink(url, url)
                            i = auto.range.last + 1
                        } else {
                            append(c); i++
                        }
                    }
                }
                !insideLink && (c == 'h' || c == 'H') && (text.startsWith("http://", i, ignoreCase = true) || text.startsWith("https://", i, ignoreCase = true)) -> {
                    val raw = bareUrl.matchAt(text, i)?.value
                    if (raw != null) {
                        val url = trimTrailingUrlPunctuation(raw)
                        emitLink(url, url)
                        i += url.length
                    } else {
                        append(c); i++
                    }
                }
                else -> {
                    append(c); i++
                }
            }
        }
    }

    /**
     * CommonMark inline link at [start]: `[label](destination)`, optional space before `(`, `<>` around the
     * destination, optional quoted title. Label may contain `#` (the usual `[PR #66](…)` shape).
     */
    internal fun parseMarkdownLink(text: String, start: Int): InlineLink? {
        if (start >= text.length || text[start] != '[') return null
        val close = findBalanced(text, start, '[', ']') ?: return null
        val label = text.substring(start + 1, close)
        if (label.isEmpty()) return null
        var i = close + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '(') return null
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val url: String
        if (text[i] == '<') {
            val gt = text.indexOf('>', i + 1)
            if (gt < 0) return null
            url = text.substring(i + 1, gt).trim()
            i = gt + 1
        } else {
            val from = i
            while (i < text.length && text[i] != ')' && !text[i].isWhitespace()) i++
            url = text.substring(from, i)
        }
        if (url.isEmpty()) return null
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && (text[i] == '"' || text[i] == '\'')) {
            val q = text[i]
            val endTitle = text.indexOf(q, i + 1)
            if (endTitle < 0) return null
            i = endTitle + 1
            while (i < text.length && text[i].isWhitespace()) i++
        }
        if (i >= text.length || text[i] != ')') return null
        return InlineLink(label, url, i + 1)
    }

    /** Matching closer for [open]/[close], skipping `\[` escapes and nested pairs. */
    private fun findBalanced(text: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                open -> {
                    depth++
                    i++
                }
                close -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    /** GFM autolink: drop a trailing sentence mark that is almost never part of the URL. */
    private fun trimTrailingUrlPunctuation(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in ".,;:!?") end--
        // A closing paren is trailing punctuation unless the URL itself opened one (wikipedia-style /Foo_(bar)).
        if (end > 0 && url[end - 1] == ')' && url.count { it == '(' } < url.count { it == ')' }) end--
        return url.substring(0, end).ifEmpty { url }
    }

    data class InlineLink(val label: String, val url: String, val end: Int)
}

/**
 * Renders agent markdown. Images and videos resolve through [LocalMarkdownMedia] (artifact paths need the
 * agent's download endpoint); outside a conversation only absolute URLs and data URIs can be shown.
 *
 * [streaming] trims a half-received media tag from the end of the text so the raw markup never flashes.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CursorTheme.typography.message,
    color: Color = CursorTheme.colors.textPrimary,
    streaming: Boolean = false,
) {
    val colors = CursorTheme.colors
    val parser = remember { IncrementalMarkdown() }
    val blocks = remember(markdown, streaming) {
        parser.parse(if (streaming) MediaMarkup.trimPartialTail(markdown) else markdown)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Keyed on position rather than content: streaming only appends, so every block but the last keeps its
        // index, and a code block being written keeps the horizontal scroll the reader put it at.
        blocks.forEachIndexed { index, block ->
            key(index, block::class) {
                when (block) {
                    is MdBlock.Paragraph -> InlineText(block.text, style, color)
                    is MdBlock.Heading -> {
                        val headingStyle = when (block.level) {
                            1 -> style.copy(fontSize = style.fontSize * 1.25f, fontWeight = FontWeight.SemiBold)
                            2 -> style.copy(fontSize = style.fontSize * 1.12f, fontWeight = FontWeight.SemiBold)
                            else -> style.copy(fontWeight = FontWeight.SemiBold)
                        }
                        InlineText(block.text, headingStyle, color, modifier = Modifier.padding(top = 4.dp))
                    }
                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row(Modifier.padding(start = (item.depth * 16).dp)) {
                                Text(
                                    item.number?.let { "$it." } ?: "•",
                                    style = style,
                                    color = colors.textTertiary,
                                    modifier = Modifier.width(22.dp),
                                )
                                InlineContent(item.text, style, color, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    is MdBlock.Code -> CodeBlock(block.code, block.language)
                    is MdBlock.Quote -> Row {
                        Box(Modifier.width(2.dp).padding(vertical = 2.dp).background(colors.strokeStrong))
                        InlineContent(block.text, style, colors.textTertiary, modifier = Modifier.padding(start = 10.dp))
                    }
                    MdBlock.Rule -> HairlineDivider(Modifier.padding(vertical = 4.dp))
                    is MdBlock.Image -> ImageBlock(block.src, block.alt)
                    is MdBlock.Video -> VideoBlock(block.src, block.poster)
                }
            }
        }
    }
}

/** Text that may still carry media markup (list items, quotes): text runs and media stacked in order. */
@Composable
private fun InlineContent(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val segments = remember(text) { MediaMarkup.split(text) }
    if (segments.size == 1 && segments[0] is MediaSegment.Text) {
        InlineText((segments[0] as MediaSegment.Text).text, style, color, modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MediaSegment.Text -> InlineText(segment.text, style, color)
                is MediaSegment.Image -> ImageBlock(segment.src, segment.alt)
                is MediaSegment.Video -> VideoBlock(segment.src, segment.poster)
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val uriHandler = LocalUriHandler.current
    val openLink = remember(uriHandler) { InlineMarkdown.opener(uriHandler) }
    val annotated = remember(text, style, color, openLink) {
        InlineMarkdown.render(
            text = text,
            base = style,
            codeColor = colors.textPrimary,
            codeBackground = colors.fillMedium,
            linkColor = colors.link,
            boldColor = colors.textPrimary,
            onLinkClick = openLink,
        )
    }
    Text(text = annotated, style = style.copy(color = color), modifier = modifier)
}

@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    CursorCard(modifier = modifier.fillMaxWidth(), shape = CursorTheme.shapes.lg, fill = colors.canvas, border = colors.strokeSubtle) {
        if (!language.isNullOrBlank()) {
            Text(
                language,
                style = CursorTheme.typography.tiny,
                color = colors.textQuaternary,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        Text(
            code,
            style = CursorTheme.typography.codeBlock,
            color = colors.textPrimary,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
