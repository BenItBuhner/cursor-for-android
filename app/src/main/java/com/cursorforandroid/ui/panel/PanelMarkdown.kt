package com.cursorforandroid.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.AgentLink
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.components.CodeBlock
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.InlineMarkdown
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.ui.components.MdBlock
import com.cursorforandroid.ui.components.TableBlock
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorTheme

/** The store a document came from and the folder it sits in: what its relative links are read against. */
internal data class StoreBase(val storeId: String, val folder: String) {
    companion object {
        fun of(storeId: String, path: String): StoreBase = StoreBase(storeId, path.trim('/').substringBeforeLast('/', ""))
    }
}

/**
 * Markdown as cursor.com's right panel sets a Project's notes and its documents, rather than as a reply in the chat:
 * body text at 14/21 in the text's own colour, semibold headings with air above each new one, task items behind
 * rings (a done one ticked), and links bold and underlined in the text's colour, each led by what it points at —
 * GitHub's mark before a release or a repository, the pull-request glyph before a pull request, the markdown glyph
 * before a document. A relative link is read against [base], so the notes' `[Project context](docs/project-context.md)`
 * is that document of the same store. A tapped link goes where the chat's would ([rememberPanelLinkOpener]) unless
 * [onOpenLink] takes it.
 */
@Composable
internal fun PanelMarkdown(markdown: String, modifier: Modifier = Modifier, base: StoreBase? = null, onOpenLink: ((String) -> Unit)? = null) {
    val routed = rememberPanelLinkOpener()
    val blocks = remember(markdown, base) { MarkdownCache.parse(if (base == null) markdown else PanelLinks.resolve(markdown, base)) }
    PanelBlocks(blocks, onOpenLink ?: routed, modifier, gap = BlockGap, color = CursorTheme.colors.textPrimary)
}

/**
 * Where a link tapped in the panel goes, as the chat's inline text sends it: an agent's link and a store path to the
 * surface's [LocalMarkdownMedia] hands — the panel's own tabs — else to their pages on cursor.com; anything else to
 * the system.
 */
@Composable
internal fun rememberPanelLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val media = LocalMarkdownMedia.current
    return remember(uriHandler, media) {
        val system = InlineMarkdown.opener(uriHandler)
        val open: (String) -> Unit = { url ->
            val agent = AgentLink.parse(url)
            val store = if (agent == null) StorePath.parse(url) else null
            when {
                agent != null -> media?.onOpenAgentLink?.invoke(agent) ?: system(agent.webUrl)
                store == null -> system(url)
                media?.onOpenStorePath != null -> media.onOpenStorePath.invoke(store)
                else -> store.ownerId(media?.agentId)?.let { system(StorePath.webUrl(it)) }
            }
        }
        open
    }
}

@Composable
private fun PanelBlocks(blocks: List<MdBlock>, open: (String) -> Unit, modifier: Modifier, gap: Dp, color: Color) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            key(index, block::class) {
                if (index > 0) Spacer(Modifier.height(if (block is MdBlock.Heading) gap + HeadingLead else gap))
                PanelBlock(block, open, color)
            }
        }
    }
}

@Composable
private fun PanelBlock(block: MdBlock, open: (String) -> Unit, color: Color) {
    val colors = CursorTheme.colors
    val body = PanelType.body()
    when (block) {
        is MdBlock.Paragraph -> PanelInline(block.text, body, color, open)
        is MdBlock.Heading -> PanelInline(block.text, PanelType.heading(block.level), colors.textPrimary, open, Modifier.semantics { heading() })
        is MdBlock.Bullets -> PanelList(block, open, color)
        is MdBlock.Code -> CodeBlock(block.code, block.language)
        is MdBlock.Quote -> {
            val bar = colors.strokeStrong
            PanelBlocks(
                block.blocks,
                open,
                Modifier
                    .drawBehind { drawRect(bar, Offset(0f, 2.dp.toPx()), Size(2.dp.toPx(), (size.height - 4.dp.toPx()).coerceAtLeast(0f))) }
                    .padding(start = 12.dp),
                gap = 8.dp,
                color = colors.textSecondary,
            )
        }
        MdBlock.Rule -> HairlineDivider(Modifier.padding(vertical = 4.dp))
        is MdBlock.Image -> ImageBlock(block.src, block.alt)
        is MdBlock.Video -> VideoBlock(block.src, block.poster)
        is MdBlock.Table -> TableBlock(block, body, color)
    }
}

/** A list as the web's notes draw one: the marker in a 22dp column on the first line — a ring for a task, a dot, a number — and the item beside it. */
@Composable
private fun PanelList(block: MdBlock.Bullets, open: (String) -> Unit, color: Color) {
    val colors = CursorTheme.colors
    val body = PanelType.body()
    val line = with(LocalDensity.current) { body.lineHeight.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Box(Modifier.widthIn(min = MarkerWidth).height(line).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        item.checked != null -> TaskRing(item.checked)
                        block.ordered -> Text("${block.start + index}.", style = body, color = colors.textTertiary)
                        else -> Box(Modifier.padding(start = 5.dp).size(5.dp).background(colors.textTertiary, CircleShape))
                    }
                }
                PanelBlocks(item.blocks, open, Modifier.weight(1f), gap = 8.dp, color = color)
            }
        }
    }
}

/** The web's task marker: a ring, ticked when the item is done. */
@Composable
private fun TaskRing(checked: Boolean) {
    val colors = CursorTheme.colors
    Box(
        Modifier
            .size(RingSize)
            .border(1.dp, colors.iconQuaternary, CircleShape)
            .semantics { contentDescription = if (checked) "Done" else "To do" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(CursorIcons.Check, null, tint = colors.iconTertiary, modifier = Modifier.size(9.dp))
    }
}

@Composable
private fun PanelInline(text: String, style: TextStyle, color: Color, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val linkStyle = remember(colors) { TextLinkStyles(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) }
    val annotated = remember(text, style, colors, open) {
        val rendered = InlineMarkdown.render(
            text = text,
            base = style,
            codeColor = colors.textPrimary,
            codeBackground = colors.fill,
            linkColor = colors.textPrimary,
            boldColor = colors.textPrimary,
            onLinkClick = open,
        )
        PanelLinks.restyle(rendered, linkStyle)
    }
    val glyphs = rememberLinkGlyphs(colors)
    Text(annotated, style = style.copy(color = color), inlineContent = glyphs, modifier = modifier)
}

/** What a link leads with, by what it points at. */
internal enum class LinkGlyph(val id: String) {
    GitHub("panel-link-github"),
    PullRequest("panel-link-pr"),
    Document("panel-link-doc"),
    ;

    val icon: ImageVector get() = when (this) {
        GitHub -> PanelIcons.GitHub
        PullRequest -> CursorIcons.GitPullRequest
        Document -> PanelIcons.Markdown
    }

    fun tint(colors: CursorColors): Color = when (this) {
        GitHub -> colors.textPrimary
        PullRequest -> PullRequestViolet
        Document -> colors.iconTertiary
    }
}

@Composable
private fun rememberLinkGlyphs(colors: CursorColors): Map<String, InlineTextContent> = remember(colors) {
    LinkGlyph.entries.associate { glyph ->
        glyph.id to InlineTextContent(Placeholder(width = GlyphWidth, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Icon(glyph.icon, null, tint = glyph.tint(colors), modifier = Modifier.fillMaxHeight().aspectRatio(1f))
            }
        }
    }
}

/** The panel's reading of links: which glyph leads each, relative targets read against their store, and the web's link style. */
internal object PanelLinks {
    private val pullRequest = Regex("""^https?://(www\.)?github\.com/[^/]+/[^/]+/pull/\d+""", RegexOption.IGNORE_CASE)
    private val gitHub = Regex("""^https?://(www\.)?github\.com/""", RegexOption.IGNORE_CASE)
    private val scheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

    /** `[label](target)`, not an image's `![alt](src)`. */
    private val link = Regex("""(?<!!)\[([^\[\]]*)]\(([^()\s]+)\)""")

    fun glyphFor(target: String): LinkGlyph? = when {
        pullRequest.containsMatchIn(target) -> LinkGlyph.PullRequest
        gitHub.containsMatchIn(target) -> LinkGlyph.GitHub
        StorePath.parse(target)?.isMarkdown == true -> LinkGlyph.Document
        else -> null
    }

    /** [markdown] with each relative link made the store path it names under [base]; the rest as written. */
    fun resolve(markdown: String, base: StoreBase): String = link.replace(markdown) { match ->
        val resolved = resolveTarget(match.groupValues[2], base) ?: return@replace match.value
        "[${match.groupValues[1]}]($resolved)"
    }

    /**
     * The store path [target] names when it is relative to [base]'s folder (`docs/a.md`, `./a.md`, `../a.md`); null
     * for an address, an absolute path, a fragment, an agent's id, or a path that would climb out of the store.
     */
    fun resolveTarget(target: String, base: StoreBase): String? {
        if (target.startsWith("#") || target.startsWith("/") || scheme.containsMatchIn(target) || AgentLink.parse(target) != null) return null
        val path = target.substringBefore('#').substringBefore('?')
        val segments = base.folder.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        if (segments.isEmpty()) return null
        return StorePath.ROOT + base.storeId + "/" + segments.joinToString("/")
    }

    /**
     * [rendered] with its links in [style] rather than the chat's link colour, each led by its glyph ([glyphFor]),
     * set outside the link so it takes no underline. A code span that opens like a link keeps the look of code.
     */
    fun restyle(rendered: AnnotatedString, style: TextLinkStyles): AnnotatedString {
        val links = rendered.getLinkAnnotations(0, rendered.length).filter { it.item is LinkAnnotation.Url }.sortedBy { it.start }
        if (links.isEmpty()) return rendered
        fun isCode(start: Int, end: Int) = rendered.getStringAnnotations(InlineMarkdown.CODE_SPAN, start, end).isNotEmpty()
        val glyphs = links.mapNotNull { range ->
            if (isCode(range.start, range.end)) null else glyphFor((range.item as LinkAnnotation.Url).url)?.let { range.start to it }
        }
        val inserts = glyphs.map { it.first }
        fun before(offset: Int) = inserts.count { it < offset }
        fun upTo(offset: Int) = inserts.count { it <= offset }
        return buildAnnotatedString {
            var at = 0
            glyphs.forEach { (offset, glyph) ->
                append(rendered.text, at, offset)
                appendInlineContent(glyph.id, "\uFFFD")
                at = offset
            }
            append(rendered.text, at, rendered.length)
            rendered.spanStyles.forEach { addStyle(it.item, it.start + before(it.start), it.end + before(it.end)) }
            rendered.paragraphStyles.forEach { addStyle(it.item, it.start + before(it.start), it.end + before(it.end)) }
            rendered.getStringAnnotations(0, rendered.length).forEach { addStringAnnotation(it.tag, it.item, it.start + before(it.start), it.end + before(it.end)) }
            links.forEach { range ->
                val url = range.item as LinkAnnotation.Url
                val styles = if (isCode(range.start, range.end)) url.styles else style
                addLink(LinkAnnotation.Url(url.url, styles, url.linkInteractionListener), range.start + upTo(range.start), range.end + before(range.end))
            }
        }
    }
}

/** The panel's type, as measured off cursor.com's notes and documents. */
internal object PanelType {
    @Composable
    fun body(): TextStyle = CursorTheme.typography.message.copy(fontSize = 14.sp, lineHeight = 21.sp)

    @Composable
    fun heading(level: Int): TextStyle = when (level) {
        1 -> body().copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        2 -> body().copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
        3 -> body().copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        else -> body().copy(fontWeight = FontWeight.SemiBold)
    }

    /** A pane's title over its content: the Project's name, an agent's. */
    @Composable
    fun title(): TextStyle = heading(2)
}

/** GitHub's pull-request violet, as the web's notes tint the glyph before a pull request's number. */
private val PullRequestViolet = Color(0xFF8C86FA)

private val BlockGap = 14.dp
private val HeadingLead = 22.dp
private val ItemGap = 14.dp
private val MarkerWidth = 22.dp
private val RingSize = 14.dp
private val GlyphWidth = 1.35.em
