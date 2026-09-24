package com.cursorforandroid.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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

    /** The annotation an inline code span is rendered under; its item is the code itself, without the padding spaces. */
    const val CODE_SPAN = "code"

    /** The inline code span of [annotated] that [offset] falls in, if any. */
    fun codeSpanAt(annotated: AnnotatedString, offset: Int): String? =
        annotated.getStringAnnotations(CODE_SPAN, 0, annotated.length).firstOrNull { offset >= it.start && offset < it.end }?.item

    /** Whether [offset] of [annotated] is a link: a code span that opens like one keeps its tap and is not claimed. */
    fun isLinkAt(annotated: AnnotatedString, offset: Int): Boolean =
        annotated.getLinkAnnotations(0, annotated.length).any { offset >= it.start && offset < it.end }

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
        // A path into an Agent Store is opened here (the document sheet, or the Project on cursor.com), so it is a link.
        StorePath.parse(target)?.let { return it.text }
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
        val commandTints: CommandTints?,
        /** The `/command` tokens of the text being rendered, by the index of their slash in it; empty without [commandTints]. */
        val commands: Map<Int, IntRange>,
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
     * With [commandTints] — the reader's own words, not an agent's — the `/command` tokens of [text] are painted
     * in their tints, the rest left as it is: the tokens are the composer's own ([SlashCommands.tokenRanges] of the very same
     * text), found before any markup is read and painted where the markup leaves them standing, so a `/goal` the
     * field showed as a command is one in the bubble, and a slash the field did not paint — one right after a star,
     * a bracket or a tag rather than whitespace — stays as it was. Only a code span keeps its own colour: code is code.
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
        commandTints: CommandTints? = null,
    ): AnnotatedString {
        val commands = if (commandTints != null) SlashCommands.tokenRanges(text).associateBy { it.first } else emptyMap()
        val palette = Palette(base, codeColor, codeBackground, linkColor, boldColor, commandTints, commands, onLinkClick)
        return buildAnnotatedString { appendInline(text, palette, insideLink = false, offset = 0) }
    }

    /**
     * Appends [text] with its inline markup rendered. [offset] is where [text] begins in the string [render] was
     * given: the markup is read recursively on the pieces it encloses, and the command tokens ([Palette.commands])
     * are indexed in the whole.
     */
    private fun AnnotatedString.Builder.appendInline(text: String, p: Palette, insideLink: Boolean, offset: Int) {
        var i = 0
        val n = text.length
        // Where a search for an emphasis closer last ran off the end, per delimiter and run length: a later opener
        // of the same kind cannot find one either, so a paragraph of unclosed `*item` is read once, not once per star.
        val noCloserFrom = IntArray(8) { Int.MAX_VALUE }
        /** Renders [inner], the piece of [text] that starts at [at], inside the style the caller has pushed. */
        fun recurse(inner: String, at: Int) = appendInline(inner, p, insideLink, offset + at)
        fun emitLink(url: String, label: String, labelAt: Int) {
            // Labels keep bold/code/italic, but not nested links — a bare-URL label would recurse forever. An address
            // nothing on the device can open is not made into a link at all (see [linkTarget]).
            val target = linkTarget(url)
            if (target == null) {
                appendInline(label, p, insideLink = true, offset + labelAt)
                return
            }
            val listener = p.onLinkClick?.let { click -> LinkInteractionListener { click(target) } }
            withLink(LinkAnnotation.Url(url = target, styles = p.link, linkInteractionListener = listener)) {
                appendInline(label, p, insideLink = true, offset + labelAt)
            }
        }
        fun emitCode(code: String) {
            // A code span that is a store path, the way a coordinator names a document, opens like a link and reads like code.
            val store = if (!insideLink) StorePath.parse(code)?.text else null
            val listener = store?.let { target -> p.onLinkClick?.let { click -> LinkInteractionListener { click(target) } } }
            // The span carries its own text, so a press-and-hold on it can copy the code and not the padding around it.
            pushStringAnnotation(CODE_SPAN, code)
            if (store != null && listener != null) {
                // Code in the link colour: what a `[`path`](path)` link reads as, so both spellings look the same.
                val style = p.link.style?.let { p.code.merge(it) } ?: p.code
                withLink(LinkAnnotation.Url(url = store, styles = TextLinkStyles(style = style), linkInteractionListener = listener)) { append(" $code ") }
            } else {
                withStyle(p.code) { append(" $code ") }
            }
            pop()
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
                        withStyle(p.strike) { recurse(text.substring(i + 2, end), i + 2) }
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
                                3 -> withStyle(p.bold) { withStyle(p.italic) { recurse(inner, i + len) } }
                                2 -> withStyle(p.bold) { recurse(inner, i + len) }
                                else -> withStyle(p.italic) { recurse(inner, i + len) }
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
                        // The label opens one past the `[`.
                        emitLink(link.url, link.label, i + 1)
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
                        recurse(image.label, i + 2)
                        i = image.end
                    } else {
                        append(c)
                        i++
                    }
                }
                c == '<' -> i = appendHtml(text, i, p, insideLink, ::emitLink, ::emitCode, ::recurse)
                // A `/command` the composer painted: found on the whole text before any markup was read, so the
                // slash is one the reader's field showed as a command and not one a delimiter left standing first.
                c == '/' && p.commandTints != null && p.commands.containsKey(offset + i) -> {
                    // A token is `/[a-z0-9-]+` closed by whitespace or the end, and the markup's pieces are cut at
                    // delimiters, never inside a token: the whole of it is in this piece.
                    val end = p.commands.getValue(offset + i).last + 1 - offset
                    withStyle(SpanStyle(color = p.commandTints.forToken(text.substring(i + 1, end)))) { append(text, i, end) }
                    i = end
                }
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
                        emitLink(url, url, i)
                        i += url.length
                    } else {
                        append(c)
                        i++
                    }
                }
                // A bare store path in prose (`see /cursor/stores/bc-…/docs/spec.md`) is a link like a bare URL.
                !insideLink && c == '/' && text.startsWith(StorePath.ROOT, i) -> {
                    val path = StorePath.findBare(text, i)
                    if (path != null) {
                        emitLink(path, path, i)
                        i += path.length
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

    /**
     * Handles the `<` at [i]: a line break, anchor, autolink or a known inline tag. Returns the index to continue
     * from. [emitLink] and [recurse] take the text they are handed and where in [text] it starts.
     */
    private fun AnnotatedString.Builder.appendHtml(
        text: String,
        i: Int,
        p: Palette,
        insideLink: Boolean,
        emitLink: (url: String, label: String, labelAt: Int) -> Unit,
        emitCode: (String) -> Unit,
        recurse: (inner: String, at: Int) -> Unit,
    ): Int {
        lineBreakTag.matchAt(text, i)?.let {
            append('\n')
            return it.range.last + 1
        }
        if (!insideLink) {
            htmlAnchor.matchAt(text, i)?.let {
                emitLink(it.groupValues[1].trim(), it.groupValues[2], it.groups[2]!!.range.first)
                return it.range.last + 1
            }
            autolink.matchAt(text, i)?.let {
                emitLink(it.groupValues[1], it.groupValues[1], it.groups[1]!!.range.first)
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
            Tag.Bold -> withStyle(p.bold) { recurse(inner, tagEnd) }
            Tag.Italic -> withStyle(p.italic) { recurse(inner, tagEnd) }
            Tag.Code -> emitCode(decodeEntities(inner))
            Tag.Strike -> withStyle(p.strike) { recurse(inner, tagEnd) }
            Tag.Underline -> withStyle(p.underline) { recurse(inner, tagEnd) }
            Tag.Subscript -> withStyle(p.subscript) { recurse(inner, tagEnd) }
            Tag.Superscript -> withStyle(p.superscript) { recurse(inner, tagEnd) }
            Tag.Mark -> withStyle(p.mark) { recurse(inner, tagEnd) }
            Tag.Transparent -> recurse(inner, tagEnd)
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
 *
 * [commandTints] is for the reader's own words: with it, the `/command` tokens the composer painted are painted
 * again in the same tints ([InlineMarkdown.render]) — the transcript's bubbles give it the theme's ([CommandTints.forTheme]); a reply,
 * which is nobody's command, leaves it null.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CursorTheme.typography.message,
    color: Color = CursorTheme.colors.textPrimary,
    streaming: Boolean = false,
    commandTints: CommandTints? = null,
) {
    // A reply still arriving re-reads only its tail (see [IncrementalMarkdown]); a finished message is parsed once
    // for as long as it stands, wherever it is drawn (see [MarkdownCache]) — the presenter has usually parsed the
    // newest page's before the row is composed, off the main thread.
    val parser = remember { IncrementalMarkdown() }
    val blocks = remember(markdown, streaming) {
        if (streaming) parser.parse(MediaMarkup.trimPartialTail(markdown)) else MarkdownCache.parse(markdown)
    }
    MarkdownBlocks(blocks, style, color, modifier, spacing = 10.dp, commandTints = commandTints)
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, style: TextStyle, color: Color, modifier: Modifier = Modifier, spacing: Dp, commandTints: CommandTints?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        // Keyed on position rather than content: streaming only appends, so every block but the last keeps its
        // index, and a code block being written keeps the horizontal scroll the reader put it at.
        blocks.forEachIndexed { index, block ->
            key(index, block::class) { MarkdownBlock(block, style, color, commandTints) }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: TextStyle, color: Color, commandTints: CommandTints?) {
    val colors = CursorTheme.colors
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.text, style, color, commandTints = commandTints)
        is MdBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> style.copy(fontSize = style.fontSize * 1.25f, fontWeight = FontWeight.SemiBold)
                2 -> style.copy(fontSize = style.fontSize * 1.12f, fontWeight = FontWeight.SemiBold)
                else -> style.copy(fontWeight = FontWeight.SemiBold)
            }
            InlineText(block.text, headingStyle, color, modifier = Modifier.padding(top = 4.dp), commandTints = commandTints)
        }
        is MdBlock.Bullets -> ListBlock(block, style, color, commandTints)
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
                commandTints = commandTints,
            )
        }
        MdBlock.Rule -> HairlineDivider(Modifier.padding(vertical = 4.dp))
        is MdBlock.Image -> ImageBlock(block.src, block.alt)
        is MdBlock.Video -> VideoBlock(block.src, block.poster)
        is MdBlock.Table -> TableBlock(block, style, color, commandTints = commandTints)
    }
}

/**
 * A list: a marker column (bullet, number or task checkbox) beside each item's blocks. The column is 22dp, widened
 * only when the list counts into two or more digits so "10." is never wrapped onto two lines.
 */
@Composable
private fun ListBlock(block: MdBlock.Bullets, style: TextStyle, color: Color, commandTints: CommandTints?) {
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
                MarkdownBlocks(item.blocks, style, color, Modifier.weight(1f), spacing = 6.dp, commandTints = commandTints)
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
internal fun InlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, commandTints: CommandTints? = null) {
    val colors = CursorTheme.colors
    val uriHandler = LocalUriHandler.current
    val media = LocalMarkdownMedia.current
    // A store link goes where the screen sends it — the document sheet — or to the Project on cursor.com when
    // nothing here reads the store; anything else to the system.
    val openLink: (String) -> Unit = remember(uriHandler, media) {
        val system = InlineMarkdown.opener(uriHandler)
        val open: (String) -> Unit = { url ->
            val store = StorePath.parse(url)
            when {
                store == null -> system(url)
                media?.onOpenStorePath != null -> media.onOpenStorePath.invoke(store)
                else -> store.ownerId(media?.agentId)?.let { system(StorePath.webUrl(it)) }
            }
        }
        open
    }
    val annotated = remember(text, style, color, openLink, commandTints) {
        InlineMarkdown.render(
            text = text,
            base = style,
            codeColor = colors.textPrimary,
            codeBackground = colors.fillMedium,
            linkColor = colors.link,
            boldColor = colors.textPrimary,
            onLinkClick = openLink,
            commandTints = commandTints,
        )
    }
    // Press and hold on an inline code span copies it, as the block's button does its code. The text and its layout
    // are kept in a plain holder read at press time, so a paragraph still arriving — a new string every delta —
    // neither recomposes nor restarts the gesture for it.
    val copy = rememberCopyCode()
    val paragraph = remember { ParagraphHolder() }
    paragraph.annotated = annotated
    Text(
        text = annotated,
        style = style.copy(color = color),
        onTextLayout = { paragraph.layout = it },
        modifier = modifier.copyInlineCodeOnLongPress(paragraph, copy),
    )
}

/** A paragraph as it stands: its text, written as it is composed, and its layout, written as it lays out. */
private class ParagraphHolder {
    var annotated: AnnotatedString? = null
    var layout: TextLayoutResult? = null
}

/**
 * Copies an inline code span that is pressed and held, the way cursor.com's chat does. The press is claimed in the
 * initial pass, and only when it lands on a code span that is not also a link: the message's own press-and-hold
 * menu (a parent, which waits for an unclaimed press) then stays shut, while the paragraph's links — and a code span
 * that opens a document — keep their taps. A plain tap on code does nothing, as before. Once the hold has copied, the
 * rest of the gesture is swallowed so the release is not read as a tap by whatever sits under the finger.
 */
private fun Modifier.copyInlineCodeOnLongPress(paragraph: ParagraphHolder, onCopy: (String) -> Unit): Modifier =
    pointerInput(paragraph) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val annotated = paragraph.annotated ?: return@awaitEachGesture
            val result = paragraph.layout ?: return@awaitEachGesture
            val position = down.position
            val line = result.getLineForVerticalPosition(position.y)
            // Off the end of a line the nearest offset is still a character; a press there is not on the code.
            if (position.x < result.getLineLeft(line) || position.x > result.getLineRight(line)) return@awaitEachGesture
            val offset = result.getOffsetForPosition(position)
            if (InlineMarkdown.isLinkAt(annotated, offset)) return@awaitEachGesture
            val code = InlineMarkdown.codeSpanAt(annotated, offset) ?: return@awaitEachGesture
            down.consume()
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial) ?: Cancelled
            }
            if (released != null) return@awaitEachGesture
            onCopy(code)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** What [waitForUpOrCancellation] returning null stands for, so it can be told from the hold running out of time. */
private object Cancelled

/**
 * Copies code to the clipboard with a tick and the confirmation the message menu gives: the system's own overlay
 * on Android 13+, a toast before that.
 */
@Composable
internal fun rememberCopyCode(): (String) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    return remember(context, clipboard, haptics) {
        { code: String ->
            clipboard.setText(AnnotatedString(code))
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * A fenced code block as cursor.com/agents draws one: a strip along the top with the language on the left and a
 * copy button on the right, then the code. The strip sits outside the code's horizontal scroll, so it stays put
 * while long lines are dragged under it, and the button copies the block's raw text — never what happens to be
 * on screen. A block still arriving keeps its instance across deltas (the renderer keys blocks by position), so
 * the strip, the scroll and a "Copied" tick all outlive the text changing under them.
 */
@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    CursorCard(modifier = modifier.fillMaxWidth(), shape = CursorTheme.shapes.lg, fill = colors.canvas, border = colors.strokeSubtle) {
        Row(
            Modifier.fillMaxWidth().height(CodeHeaderHeight).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.orEmpty(),
                style = CursorTheme.typography.tiny,
                color = colors.textQuaternary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyCodeButton(code)
        }
        HairlineDivider()
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

/** The strip's copy button: the copy glyph, a tick for a moment after it has copied. */
@Composable
private fun CopyCodeButton(code: String) {
    val copy = rememberCopyCode()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedTickMillis)
            copied = false
        }
    }
    FlatIconButton(
        icon = if (copied) CursorIcons.Check else CursorIcons.Copy,
        contentDescription = if (copied) "Copied" else "Copy code",
        onClick = {
            copy(code)
            copied = true
        },
        size = 24.dp,
        iconSize = 14.dp,
        tint = if (copied) CursorTheme.colors.green else CursorTheme.colors.iconTertiary,
    )
}

private val CodeHeaderHeight = 30.dp
private const val CopiedTickMillis = 1_500L
