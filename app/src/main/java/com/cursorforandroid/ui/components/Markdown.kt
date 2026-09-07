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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
}

object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^\\s*\\d+[.)]\\s+(.*)$")

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flushParagraph()
                    val lang = line.trim().removePrefix("```").trim().ifEmpty { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                line.isBlank() -> flushParagraph()
                headingRegex.matches(line) -> {
                    flushParagraph()
                    val m = headingRegex.find(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }
                line.trim() == "---" || line.trim() == "***" -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }
                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    blocks += MdBlock.Quote(quote.toString().trim())
                    continue
                }
                bulletRegex.matches(line) || orderedRegex.matches(line) -> {
                    flushParagraph()
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
                    blocks += MdBlock.Bullets(items, ordered)
                    continue
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }
}

object InlineMarkdown {
    private val linkRegex = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")

    /**
     * Renders inline code, bold, italics and links to an AnnotatedString. Inline code gets a 12% base chip in
     * JetBrains Mono, links use Cursor's textLink blue, both matching the desktop chat renderer.
     */
    fun render(
        text: String,
        base: TextStyle,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
        boldColor: Color,
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
                    val m = linkRegex.find(text, i)
                    if (m != null && m.range.first == i) {
                        withLink(
                            LinkAnnotation.Url(
                                url = m.groupValues[2],
                                styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None)),
                            ),
                        ) { append(m.groupValues[1]) }
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

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CursorTheme.typography.message,
    color: Color = CursorTheme.colors.textPrimary,
) {
    val colors = CursorTheme.colors
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
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
                            InlineText(item, style, color, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is MdBlock.Code -> CodeBlock(block.code, block.language)
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(2.dp).padding(vertical = 2.dp).background(colors.strokeStrong))
                    InlineText(block.text, style, colors.textTertiary, modifier = Modifier.padding(start = 10.dp))
                }
                MdBlock.Rule -> HairlineDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
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
