package com.cursorforandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.JetBrainsMono

object InlineMarkdown {
    private val schemeRegex = Regex("[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val htmlAnchor = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val autolink = Regex("""<(https?://[^>\s]+)>""", RegexOption.IGNORE_CASE)
    private val bareUrl = Regex("""https?://[^\s<]+""", RegexOption.IGNORE_CASE)
    private val lineBreakTag = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)(?:\s[^<>]*)?(/?)>""")
    private val entity = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
    private const val ASCII_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Inline HTML agents write in place of markdown, mapped onto the style its markdown equivalent gets. */
    private enum class Tag { Bold, Italic, Code, Strike, Underline, Subscript, Superscript, Mark, Transparent }

    private val tags = mapOf(
        "b" to Tag.Bold, "strong" to Tag.Bold, "summary" to Tag.Bold,
        "i" to Tag.Italic, "em" to Tag.Italic,
        "code" to Tag.Code, "kbd" to Tag.Code, "tt" to Tag.Code,
        "s" to Tag.Strike, "del" to Tag.Strike, "strike" to Tag.Strike,
        "u" to Tag.Underline, "ins" to Tag.Underline,
        "sub" to Tag.Subscript, "sup" to Tag.Superscript, "mark" to Tag.Mark,
        // Wrappers with no inline meaning: the tags go, the text stays.
        "p" to Tag.Transparent, "div" to Tag.Transparent, "span" to Tag.Transparent, "details" to Tag.Transparent,
        "center" to Tag.Transparent, "font" to Tag.Transparent, "small" to Tag.Transparent, "big" to Tag.Transparent,
    )

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™",
        "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "times" to "×", "divide" to "÷",
        "bull" to "•", "middot" to "·", "deg" to "°", "plusmn" to "±", "le" to "≤", "ge" to "≥", "ne" to "≠",
        "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "check" to "✓",
    )

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

    private class Palette(
        val base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
        val onLinkClick: ((String) -> Unit)?,
    ) {
        val code = SpanStyle(fontFamily = JetBrainsMono, color = codeColor, background = codeBackground, fontSize = base.fontSize * 0.9f)
        val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)
        val italic = SpanStyle(fontStyle = FontStyle.Italic)
        val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
        val underline = SpanStyle(textDecoration = TextDecoration.Underline)
        val mark = SpanStyle(background = codeBackground)
        val subscript = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = base.fontSize * 0.75f)
        val superscript = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = base.fontSize * 0.75f)
        val link = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None))
    }

    /**
     * Renders inline code, bold, italics, strikethrough, links, escapes, entities and the inline HTML agents use
     * (`<br>`, `<b>`, `<code>`, `<sub>`…) to an AnnotatedString. Nested markup is parsed (a bold wrap around a
     * `[label](url)` is still a link); inline code stays literal. Links use Cursor's textLink blue, matching the
     * desktop chat renderer.
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
    ): AnnotatedString {
        val palette = Palette(base, codeColor, codeBackground, linkColor, boldColor, onLinkClick)
        return buildAnnotatedString { appendInline(text, palette, insideLink = false) }
    }

    private fun AnnotatedString.Builder.appendInline(text: String, p: Palette, insideLink: Boolean) {
        var i = 0
        val n = text.length
        // Where a search for an emphasis closer last ran off the end, per delimiter and run length: a later opener
        // of the same kind cannot find one either, so a paragraph of unclosed `*item` is read once, not once per star.
        val noCloserFrom = IntArray(8) { Int.MAX_VALUE }
        fun recurse(inner: String) = appendInline(inner, p, insideLink)
        fun emitLink(url: String, label: String) {
            // Labels keep bold/code/italic, but not nested links — a bare-URL label would recurse forever. An address
            // nothing on the device can open is not made into a link at all (see [linkTarget]).
            val target = linkTarget(url)
            if (target == null) {
                appendInline(label, p, insideLink = true)
                return
            }
            val listener = p.onLinkClick?.let { click -> LinkInteractionListener { click(target) } }
            withLink(LinkAnnotation.Url(url = target, styles = p.link, linkInteractionListener = listener)) {
                appendInline(label, p, insideLink = true)
            }
        }
        fun emitCode(code: String) {
            withStyle(p.code) { append(" $code ") }
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < n && text[i + 1] in ASCII_PUNCTUATION -> {
                    append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val run = runLength(text, i, '`')
                    val close = findBacktickRun(text, i + run, run)
                    if (close >= 0) {
                        emitCode(codeSpanContent(text.substring(i + run, close)))
                        i = close + run
                    } else {
                        append(text, i, i + run)
                        i += run
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = if (i + 2 < n && !text[i + 2].isWhitespace()) text.indexOf("~~", i + 2) else -1
                    if (end > i + 2) {
                        withStyle(p.strike) { recurse(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append("~~")
                        i += 2
                    }
                }
                c == '*' || c == '_' -> {
                    val run = minOf(runLength(text, i, c), 3)
                    val prev = if (i > 0) text[i - 1] else ' '
                    val next = if (i + run < n) text[i + run] else ' '
                    // Left-flanking: something follows. `_` also stays literal inside a word (snake_case_name).
                    val canOpen = !next.isWhitespace() && (c == '*' || !prev.isLetterOrDigit())
                    var closed = false
                    if (canOpen) {
                        for (len in run downTo 1) {
                            val memo = (if (c == '*') 0 else 4) + len
                            if (i + len >= noCloserFrom[memo]) continue
                            val close = findEmphasisCloser(text, i + len, c, len)
                            if (close < 0) {
                                noCloserFrom[memo] = i + len
                                continue
                            }
                            val inner = text.substring(i + len, close)
                            when (len) {
                                3 -> withStyle(p.bold) { withStyle(p.italic) { recurse(inner) } }
                                2 -> withStyle(p.bold) { recurse(inner) }
                                else -> withStyle(p.italic) { recurse(inner) }
                            }
                            i = close + len
                            closed = true
                            break
                        }
                    }
                    if (!closed) {
                        append(text, i, i + run)
                        i += run
                    }
                }
                c == '[' && !insideLink -> {
                    val link = parseMarkdownLink(text, i)
                    if (link != null) {
                        emitLink(link.url, link.label)
                        i = link.end
                    } else {
                        append(c)
                        i++
                    }
                }
                c == '!' && i + 1 < n && text[i + 1] == '[' -> {
                    // An image the block parser did not lift out (inside a table cell, say): its alt text stands in.
                    val image = parseMarkdownLink(text, i + 1)
                    if (image != null) {
                        recurse(image.label)
                        i = image.end
                    } else {
                        append(c)
                        i++
                    }
                }
                c == '<' -> i = appendHtml(text, i, p, insideLink, ::emitLink, ::emitCode, ::recurse)
                c == '&' -> {
                    val m = entity.matchAt(text, i)
                    val decoded = m?.let { decodeEntity(it.groupValues[1]) }
                    if (m != null && decoded != null) {
                        append(decoded)
                        i = m.range.last + 1
                    } else {
                        append(c)
                        i++
                    }
                }
                !insideLink && (c == 'h' || c == 'H') && (text.startsWith("http://", i, ignoreCase = true) || text.startsWith("https://", i, ignoreCase = true)) -> {
                    val raw = bareUrl.matchAt(text, i)?.value
                    if (raw != null) {
                        val url = trimTrailingUrlPunctuation(raw)
                        emitLink(url, url)
                        i += url.length
                    } else {
                        append(c)
                        i++
                    }
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    /** Handles the `<` at [i]: a line break, anchor, autolink or a known inline tag. Returns the index to continue from. */
    private fun AnnotatedString.Builder.appendHtml(
        text: String,
        i: Int,
        p: Palette,
        insideLink: Boolean,
        emitLink: (String, String) -> Unit,
        emitCode: (String) -> Unit,
        recurse: (String) -> Unit,
    ): Int {
        lineBreakTag.matchAt(text, i)?.let {
            append('\n')
            return it.range.last + 1
        }
        if (!insideLink) {
            htmlAnchor.matchAt(text, i)?.let {
                emitLink(it.groupValues[1].trim(), it.groupValues[2])
                return it.range.last + 1
            }
            autolink.matchAt(text, i)?.let {
                emitLink(it.groupValues[1], it.groupValues[1])
                return it.range.last + 1
            }
        }
        val tag = htmlTag.matchAt(text, i)
        val kind = tag?.let { tags[it.groupValues[2].lowercase()] }
        if (tag == null || kind == null) {
            append('<')
            return i + 1
        }
        val tagEnd = tag.range.last + 1
        val isClosing = tag.groupValues[1] == "/"
        val isSelfClosed = tag.groupValues[3] == "/"
        // A stray closer, a self-closed tag or a wrapper with nothing to style: the tag goes, the text stays.
        if (isClosing || isSelfClosed || kind == Tag.Transparent) return tagEnd
        val name = tag.groupValues[2]
        val close = text.indexOf("</$name>", tagEnd, ignoreCase = true)
        if (close < 0) return tagEnd
        val inner = text.substring(tagEnd, close)
        when (kind) {
            Tag.Bold -> withStyle(p.bold) { recurse(inner) }
            Tag.Italic -> withStyle(p.italic) { recurse(inner) }
            Tag.Code -> emitCode(decodeEntities(inner))
            Tag.Strike -> withStyle(p.strike) { recurse(inner) }
            Tag.Underline -> withStyle(p.underline) { recurse(inner) }
            Tag.Subscript -> withStyle(p.subscript) { recurse(inner) }
            Tag.Superscript -> withStyle(p.superscript) { recurse(inner) }
            Tag.Mark -> withStyle(p.mark) { recurse(inner) }
            Tag.Transparent -> recurse(inner)
        }
        return close + name.length + 3
    }

    private fun runLength(text: String, start: Int, c: Char): Int {
        var end = start
        while (end < text.length && text[end] == c) end++
        return end - start
    }

    /** Start of the first run of exactly [length] backticks at or after [from], or -1: a code span closes with its own fence. */
    private fun findBacktickRun(text: String, from: Int, length: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] != '`') {
                j++
                continue
            }
            val run = runLength(text, j, '`')
            if (run == length) return j
            j += run
        }
        return -1
    }

    /** CommonMark: line endings become spaces and one space of padding on both sides is dropped. */
    private fun codeSpanContent(raw: String): String {
        val code = raw.replace('\n', ' ')
        return if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()) code.substring(1, code.length - 1) else code
    }

    /**
     * Start of the run of [c] that can close an emphasis opened with [length] delimiters, or -1. A closer follows
     * non-space (`**bold **` is literal), a `_` closer does not run into a word, a single `*` never closes on part
     * of a `**` (so `*a **b** c*` nests), and a `**` may close on a longer run.
     */
    private fun findEmphasisCloser(text: String, from: Int, c: Char, length: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] != c) {
                j++
                continue
            }
            val run = runLength(text, j, c)
            val prev = text[j - 1]
            val next = if (j + run < text.length) text[j + run] else ' '
            val canClose = j > from && !prev.isWhitespace() && (c == '*' || !next.isLetterOrDigit())
            if (canClose && (run == length || (length >= 2 && run > length))) return j
            j += run
        }
        return -1
    }

    private fun decodeEntity(body: String): String? {
        if (body.startsWith("#")) {
            val codePoint = if (body[1] == 'x' || body[1] == 'X') body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
            if (codePoint == null || codePoint == 0 || codePoint in 0xD800..0xDFFF || !Character.isValidCodePoint(codePoint)) return null
            return String(Character.toChars(codePoint))
        }
        return namedEntities[body]
    }

    private fun decodeEntities(text: String): String = entity.replace(text) { decodeEntity(it.groupValues[1]) ?: it.value }

    /**
     * CommonMark inline link at [start]: `[label](destination)`, optional space before `(`, `<>` around the
     * destination, balanced parentheses inside it (`/wiki/Foo_(bar)`), optional quoted title. Label may contain `#`
     * (the usual `[PR #66](…)` shape).
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
            var depth = 0
            while (i < text.length && !text[i].isWhitespace()) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (depth == 0) break else depth--
                }
                i++
            }
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
    // Re-reads only the tail of a reply that is still arriving; see [IncrementalMarkdown].
    val parser = remember { IncrementalMarkdown() }
    val blocks = remember(markdown, streaming) {
        parser.parse(if (streaming) MediaMarkup.trimPartialTail(markdown) else markdown)
    }
    MarkdownBlocks(blocks, style, color, modifier, spacing = 10.dp)
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, style: TextStyle, color: Color, modifier: Modifier = Modifier, spacing: Dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        // Keyed on position rather than content: streaming only appends, so every block but the last keeps its
        // index, and a code block being written keeps the horizontal scroll the reader put it at.
        blocks.forEachIndexed { index, block ->
            key(index, block::class) { MarkdownBlock(block, style, color) }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: TextStyle, color: Color) {
    val colors = CursorTheme.colors
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
        is MdBlock.Bullets -> ListBlock(block, style, color)
        is MdBlock.Code -> CodeBlock(block.code, block.language)
        is MdBlock.Quote -> {
            // The bar is drawn into the content's start padding, so it spans exactly what is quoted.
            val bar = colors.strokeStrong
            MarkdownBlocks(
                block.blocks,
                style,
                colors.textTertiary,
                Modifier
                    .drawBehind {
                        val inset = 2.dp.toPx()
                        drawRect(bar, Offset(0f, inset), Size(2.dp.toPx(), (size.height - 2 * inset).coerceAtLeast(0f)))
                    }
                    .padding(start = 12.dp),
                spacing = 8.dp,
            )
        }
        MdBlock.Rule -> HairlineDivider(Modifier.padding(vertical = 4.dp))
        is MdBlock.Image -> ImageBlock(block.src, block.alt)
        is MdBlock.Video -> VideoBlock(block.src, block.poster)
        is MdBlock.Table -> TableBlock(block, style, color)
    }
}

/**
 * A list: a marker column (bullet, number or task checkbox) beside each item's blocks. The column is 22dp, widened
 * only when the list counts into two or more digits so "10." is never wrapped onto two lines.
 */
@Composable
private fun ListBlock(block: MdBlock.Bullets, style: TextStyle, color: Color) {
    val colors = CursorTheme.colors
    val markerWidth = markerColumnWidth(block, style)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                when {
                    item.checked != null -> TaskCheckbox(item.checked, style, Modifier.width(markerWidth))
                    block.ordered -> Text("${block.start + index}.", style = style, color = colors.textTertiary, modifier = Modifier.width(markerWidth))
                    else -> Text("•", style = style, color = colors.textTertiary, modifier = Modifier.width(markerWidth))
                }
                MarkdownBlocks(item.blocks, style, color, Modifier.weight(1f), spacing = 6.dp)
            }
        }
    }
}

private val MarkerColumnWidth = 22.dp

@Composable
private fun markerColumnWidth(block: MdBlock.Bullets, style: TextStyle): Dp {
    val last = block.start + block.items.size - 1
    if (!block.ordered || last < 10) return MarkerColumnWidth
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(last, style, density) {
        with(density) { measurer.measure("$last.", style).size.width.toDp() + 6.dp }.coerceAtLeast(MarkerColumnWidth)
    }
}

/** The box of a `- [ ]` / `- [x]` item, centred on the first line of its text. */
@Composable
private fun TaskCheckbox(checked: Boolean, style: TextStyle, modifier: Modifier) {
    val colors = CursorTheme.colors
    val lineHeight = with(LocalDensity.current) {
        if (style.lineHeight.isSpecified && style.lineHeight.isSp) style.lineHeight.toDp() else style.fontSize.toDp() * 1.5f
    }
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.height(lineHeight).semantics { contentDescription = if (checked) "Done" else "To do" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (checked) colors.fillMedium else Color.Transparent, shape)
                .border(1.dp, colors.strokeStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(CursorIcons.Check, null, tint = colors.iconSecondary, modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
internal fun InlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
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
