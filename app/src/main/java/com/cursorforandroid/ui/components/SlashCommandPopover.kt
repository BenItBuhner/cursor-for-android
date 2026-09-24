package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The `/word` the cursor sits in: [start] is the slash's index, [end] the index after the word's last character, and
 * [query] the word without its slash, lowercased for matching.
 */
data class SlashToken(val start: Int, val end: Int, val query: String)

/** Finds and completes the `/` token being typed, so the popover can follow the cursor rather than the whole text. */
object SlashTokens {
    private val BODY = Regex("^[A-Za-z0-9-]*$")

    /**
     * The `/` token the (collapsed) cursor of [value] is in, or null: the word around the cursor has to start with a
     * slash that opens the text or follows whitespace, and its body may only hold what a command name may. A cursor
     * right before the slash is not in the token; one right after it is (the query is then empty, and everything is
     * offered).
     */
    fun at(value: TextFieldValue): SlashToken? = at(value.text, value.selection)

    /** The same for a `TextFieldState`'s text and selection. */
    fun at(text: String, selection: TextRange): SlashToken? {
        if (selection.collapsed.not()) return null
        return at(text, selection.start)
    }

    fun at(text: String, cursor: Int): SlashToken? {
        if (cursor < 1 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) end++
        // Whitespace on both sides of the cursor: no word at all.
        if (start >= end || text[start] != '/') return null
        val body = text.substring(start + 1, end)
        if (!BODY.matches(body)) return null
        return SlashToken(start, end, body.lowercase())
    }

    /**
     * A `/` phrase a model's name may be typed as — "/Opus 4", "/gpt-5.5", "/opus 4.7 max" — where the cursor is in no
     * [at] token: from a slash that opens the text or follows whitespace, on the cursor's line, to the end of the word
     * the cursor is in, made of letters, digits, spaces, dots and dashes and no longer than a model's name with its
     * parameters. Whether it names a model is the caller's to ask ([com.cursorforandroid.domain.ModelSearch]); a
     * phrase that names none is the message being written after a command.
     */
    fun phraseAt(text: String, selection: TextRange): SlashToken? {
        if (!selection.collapsed) return null
        val cursor = selection.start
        if (cursor < 2 || cursor > text.length) return null
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val start = text.lastIndexOf('/', cursor - 1)
        if (start < lineStart || (start > 0 && !text[start - 1].isWhitespace())) return null
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) end++
        val body = text.substring(start + 1, end)
        if (body.length > PHRASE_MAX || !PHRASE.matches(body)) return null
        return SlashToken(start, end, body.lowercase())
    }

    private val PHRASE = Regex("^[A-Za-z0-9][A-Za-z0-9. -]*$")
    private const val PHRASE_MAX = 48

    /**
     * [value] with the token replaced by `/name` and one space, the cursor after the space, so the argument (or the
     * message) is typed next. A space already following the token is not doubled.
     */
    fun complete(value: TextFieldValue, token: SlashToken, name: String): TextFieldValue = complete(value.text, token, name)

    /** The completed text and the cursor to place, as a [TextFieldValue], for [text] as it stands. */
    fun complete(text: String, token: SlashToken, name: String): TextFieldValue {
        val after = text.substring(token.end)
        val insertion = "/$name" + if (after.startsWith(" ")) "" else " "
        val next = text.substring(0, token.start) + insertion + after
        val cursor = token.start + insertion.length + if (after.startsWith(" ")) 1 else 0
        return TextFieldValue(next, TextRange(cursor))
    }
}

/**
 * What the `/` popover lists ([SlashMenu.items]), which row a physical keyboard has highlighted ([selection], for
 * [popoverKeys]), and which sections a "Show N more" row has opened ([expand]).
 */
@Stable
class SlashSuggestions internal constructor(val selection: PopoverSelection<SlashItem>, private val expanded: MutableState<Set<SlashSection>>) {
    val items: List<SlashItem> get() = selection.items

    /** Lists all of [section], the highlight staying on the row it was on: the first of those just shown. */
    fun expand(section: SlashSection) {
        expanded.value = expanded.value + section
    }
}

/**
 * The `/` popover's rows for [token] and [offer]. The highlight starts on the first row as the popover opens on a
 * token, and goes back to it whenever the query starts or stops being empty, as the desktop's `/` menu has it; the
 * sections a "Show N more" opened stay open until the popover opens on another token.
 */
@Composable
fun rememberSlashSuggestions(token: SlashToken?, catalog: SlashCatalog, recent: List<String>, offer: SlashOffer = SlashOffer.None): SlashSuggestions {
    val expanded = remember(token?.start) { mutableStateOf(emptySet<SlashSection>()) }
    // Held against the query rather than recomputed: this follows the composer, which follows every caret move and —
    // while a run streams — every delta, and the search walks the whole catalog.
    val query = token?.query
    val open = expanded.value
    val results = remember(query, catalog, recent, offer, open) {
        if (query == null) emptyList() else SlashMenu.items(query, catalog, recent, offer, open)
    }
    return SlashSuggestions(rememberPopoverSelection(results, token?.start, query.isNullOrEmpty()), expanded)
}

/**
 * Whether the `/` popover is up for [token]. A catalog still waiting on the agent's machine has something to say even
 * with nothing to list, which is the state the popover's notice exists for: the command being typed may be one the
 * machine has not reported yet.
 */
fun slashPopoverOpen(token: SlashToken?, suggestions: SlashSuggestions, catalog: SlashCatalog): Boolean =
    token != null && (suggestions.items.isNotEmpty() || catalog.pending)

/**
 * The composer's `/` popover, as on cursor.com/agents and the desktop: typing `/` under the cursor lists the commands
 * and skills the chat can lead with, the modes it can wear and the models it can switch to ([suggestions]), each
 * under its small header, narrowed by what follows the slash; a tap completes the token, puts the mode on (or off)
 * or sets the model. It floats over the composer's footer from the text field it is anchored to and, unlike the "+"
 * menu, takes no focus, so the keyboard stays up and typing carries on narrowing the list until it is empty or the
 * token is left. With [showHighlight], the row a physical keyboard's Enter would pick wears the highlight.
 */
@Composable
fun SlashCommandPopover(
    token: SlashToken?,
    suggestions: SlashSuggestions,
    catalog: SlashCatalog,
    showHighlight: Boolean,
    onPick: (SlashItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val results = suggestions.items
    val expanded = slashPopoverOpen(token, suggestions, catalog)
    // The popover has nothing to say once the token matches nothing (a made-up word after a slash, "/2x") and
    // nothing more is coming; it closes itself rather than hanging on to an empty card.
    LaunchedEffect(token, results.isEmpty(), catalog.pending) {
        if (token != null && results.isEmpty() && !catalog.pending) onDismiss()
    }

    // Anchored to the text, which stands [CursorDimens.composerTextInset] in from the composer's padding: drawn back
    // out by that much, the popover's corners are concentric with the composer's, as the "+" menu's are.
    CursorMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(-CursorDimens.composerTextInset, 0.dp),
        focusable = false,
        modifier = Modifier.semantics { contentDescription = "Slash commands" },
    ) {
        Column(Modifier.width(PopoverWidth).heightIn(max = PopoverMaxHeight).fadingVerticalScroll(surface = colors.elevated)) {
            results.forEachIndexed { index, item ->
                if (index == 0 || results[index - 1].section != item.section) SlashSectionHeader(item.section, first = index == 0)
                val highlighted = showHighlight && index == suggestions.selection.highlighted
                when (item) {
                    is SlashItem.Command -> SlashCommandRow(item.command, highlighted, onClick = { onPick(item) })
                    is SlashItem.Mode -> SlashModeRow(item, highlighted, onClick = { onPick(item) })
                    is SlashItem.Model -> SlashModelRow(item, highlighted, onClick = { onPick(item) })
                    is SlashItem.ShowMore -> CursorMenuItem(
                        "Show ${item.remaining} more",
                        icon = null,
                        tint = colors.textTertiary,
                        highlighted = highlighted,
                        onClick = { onPick(item) },
                    )
                }
            }
        }
        if (catalog.pending) {
            Row(Modifier.width(PopoverWidth).padding(horizontal = CursorDimens.menuTextInset, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 10.dp)
                Spacer(Modifier.width(6.dp))
                Text("Looking for the agent's own skills…", style = type.small, color = colors.textQuaternary, maxLines = 1)
            }
        }
    }
}

/** A section's small header above its first row, as the desktop's slash menu titles its groups. */
@Composable
private fun SlashSectionHeader(section: SlashSection, first: Boolean) {
    Text(
        section.title,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = CursorDimens.menuInset + CursorDimens.menuItemPadding)
            .padding(top = if (first) 2.dp else 8.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

/** A mode: its name and the desktop's line for it, its glyph in its own colour; a check while it is worn. */
@Composable
private fun SlashModeRow(item: SlashItem.Mode, highlighted: Boolean, onClick: () -> Unit) {
    val tint = pillTint(item.pill)
    CursorMenuItem(
        item.pill.label,
        icon = null,
        subtitle = item.pill.description,
        subtitleMaxLines = 1,
        highlighted = highlighted,
        trailing = {
            if (item.on) {
                Icon(CursorIcons.Check, "On", tint = CursorTheme.colors.iconSecondary, modifier = Modifier.size(CursorDimens.menuIcon))
                Spacer(Modifier.width(8.dp))
            }
            Icon(item.pill.icon, null, tint = tint, modifier = Modifier.size(CursorDimens.menuIcon))
        },
        onClick = onClick,
    )
}

/**
 * A model: its name, with the parameters a pick sets beside it when they are not the model's defaults ("Max effort ·
 * Fast"), and a check on the one the composer is on.
 */
@Composable
private fun SlashModelRow(item: SlashItem.Model, highlighted: Boolean, onClick: () -> Unit) {
    val choice = item.choice
    val variant = choice.variant
    val hint = variant?.takeIf { it != choice.model.defaultVariant }?.let(choice.model::qualifier)
    CursorMenuItem(
        choice.label,
        icon = null,
        hint = hint,
        highlighted = highlighted,
        trailing = if (item.current) ({ Icon(CursorIcons.Check, "Current model", tint = CursorTheme.colors.iconSecondary, modifier = Modifier.size(CursorDimens.menuIcon)) }) else null,
        onClick = onClick,
    )
}

/** One suggestion: `/name` with its argument hint, and the description (or origin) beneath. */
@Composable
private fun SlashCommandRow(entry: SlashCommand, highlighted: Boolean, onClick: () -> Unit) {
    val glyph = commandGlyph(entry)
    CursorMenuItem(
        entry.command,
        icon = null,
        subtitle = entry.summary,
        hint = entry.argumentHint,
        subtitleMaxLines = 1,
        highlighted = highlighted,
        trailing = if (glyph != null) ({ Icon(glyph, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(CursorDimens.menuIcon)) }) else null,
        onClick = onClick,
    )
}

/** Commands get a glyph on the right — the goal's target, multitask's loop, plan's checklist, a terminal for a machine command; skills none, as on the Skills page. */
private fun commandGlyph(entry: SlashCommand) = when {
    entry.kind != SlashCommand.Kind.Command -> null
    entry.name == "goal" -> CursorIcons.Target
    entry.name == SlashCommands.MULTITASK -> CursorIcons.Multitask
    entry.name == SlashCommands.PLAN -> CursorIcons.Plan
    else -> CursorIcons.Terminal
}

private val PopoverWidth = 300.dp
private val PopoverMaxHeight = 264.dp
