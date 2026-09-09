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
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    /** `<img src alt>` or `![alt](src)`; [src] is still the raw reference (artifact path, URL, data URI). */
    data class Image(val src: String, val alt: String?) : MdBlock
    /** `<video src poster>` (or a nested `<source src>`). */
    data class Video(val src: String, val poster: String?) : MdBlock
}

object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^\\s*\\d+[.)]\\s+(.*)$")

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
                line.trim() == "---" || line.trim() == "***" -> {
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
                bulletRegex.matches(line) || orderedRegex.matches(line) -> {
                    flushParagraph()
                    val start = lineStart[i]
                    val ordered = orderedRegex.matches(line)
                    val regex = if (ordered) orderedRegex else bulletRegex
                    val items = mutableListOf<String>()
                    while (i < lines.size && regex.matches(lines[i])) {
                        val item = StringBuilder(regex.find(lines[i])!!.groupValues[1])
                        i++
                        // continuation lines indented under the bullet
                        while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") && !regex.matches(lines[i])) {
                            item.append(' ').append(lines[i].trim())
                            i++
                        }
                        items += item.toString()
                    }
                    emit(MdBlock.Bullets(items, ordered), start)
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

object InlineMarkdown {
    private val linkRegex = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val schemeRegex = Regex("[a-zA-Z][a-zA-Z0-9+.-]*:")

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
     * Renders inline code, bold, italics and links to an AnnotatedString. Inline code gets a 12% base chip in
     * JetBrains Mono, links use Cursor's textLink blue, both matching the desktop chat renderer.
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
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = JetBrainsMono, color = codeColor, background = codeBackground, fontSize = base.fontSize * 0.9f)) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append("**"); i += 2
                    }
                }
                c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1 && (end + 1 >= n || !text[end + 1].isLetterOrDigit() || c == '*')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }
                c == '[' -> {
                    // matchAt, not find: find scans forward to the end of the text for a match that is then thrown
                    // away unless it started here, which is quadratic in a paragraph full of "[INFO]"-style brackets.
                    val m = linkRegex.matchAt(text, i)
                    if (m != null) {
                        val target = linkTarget(m.groupValues[2])
                        if (target == null) {
                            append(m.groupValues[1])
                        } else {
                            withLink(
                                LinkAnnotation.Url(
                                    url = target,
                                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None)),
                                    linkInteractionListener = onLinkClick?.let { click -> LinkInteractionListener { click(target) } },
                                ),
                            ) { append(m.groupValues[1]) }
                        }
                        i = m.range.last + 1
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
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text(
                                    if (block.ordered) "${index + 1}." else "•",
                                    style = style,
                                    color = colors.textTertiary,
                                    modifier = Modifier.width(22.dp),
                                )
                                InlineContent(item, style, color, modifier = Modifier.weight(1f))
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
