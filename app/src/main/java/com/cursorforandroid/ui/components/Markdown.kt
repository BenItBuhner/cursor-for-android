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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
    private val htmlAnchor = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val autolink = Regex("""<(https?://[^>\s]+)>""", RegexOption.IGNORE_CASE)
    private val bareUrl = Regex("""https?://[^\s<]+""", RegexOption.IGNORE_CASE)

    /**
     * Renders inline code, bold, italics, strikethrough and links to an AnnotatedString. Nested markup is parsed
     * (a bold wrap around a `[label](url)` is still a link); inline code stays literal. Links use Cursor's
     * textLink blue, matching the desktop chat renderer.
     */
    fun render(
        text: String,
        base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
    ): AnnotatedString = buildAnnotatedString {
        appendInline(text, base, codeColor, codeBackground, linkColor, boldColor, insideLink = false)
    }

    private fun AnnotatedString.Builder.appendInline(
        text: String,
        base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
        insideLink: Boolean,
    ) {
        var i = 0
        val n = text.length
        fun recurse(inner: String) = appendInline(inner, base, codeColor, codeBackground, linkColor, boldColor, insideLink)
        fun linkStyle() = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None))
        fun emitLink(url: String, label: String) {
            // Labels keep bold/code/italic, but not nested links — a bare-URL label would recurse forever.
            withLink(LinkAnnotation.Url(url = url, styles = linkStyle())) {
                appendInline(label, base, codeColor, codeBackground, linkColor, boldColor, insideLink = true)
            }
        }
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
                c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1 && (end + 1 >= n || !text[end + 1].isLetterOrDigit() || c == '*')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { recurse(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
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
    val blocks = remember(markdown, streaming) {
        val source = if (streaming) MediaMarkup.trimPartialTail(markdown) else markdown
        keyed(MarkdownParser.parse(source))
    }
    MarkdownBlocks(blocks, style, color, modifier, spacing = 10.dp)
}

/** Each block paired with how many equal blocks precede it, so a media block keeps its loaded state while text streams in above it. */
private fun keyed(blocks: List<MdBlock>): List<Pair<MdBlock, Int>> {
    val seen = HashMap<MdBlock, Int>()
    return blocks.map { block -> block to (seen[block] ?: 0).also { seen[block] = it + 1 } }
}

@Composable
private fun MarkdownBlocks(blocks: List<Pair<MdBlock, Int>>, style: TextStyle, color: Color, modifier: Modifier = Modifier, spacing: Dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEach { (block, occurrence) ->
            key(block, occurrence) { MarkdownBlock(block, style, color) }
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
            val inner = remember(block.blocks) { keyed(block.blocks) }
            MarkdownBlocks(
                inner,
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
                val inner = remember(item.blocks) { keyed(item.blocks) }
                MarkdownBlocks(inner, style, color, Modifier.weight(1f), spacing = 6.dp)
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
    val annotated = remember(text, style, color) {
        InlineMarkdown.render(
            text = text,
            base = style,
            codeColor = colors.textPrimary,
            codeBackground = colors.fillMedium,
            linkColor = colors.link,
            boldColor = colors.textPrimary,
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
