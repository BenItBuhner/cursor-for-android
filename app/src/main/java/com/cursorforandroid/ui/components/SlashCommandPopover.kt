package com.cursorforandroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
 * The composer's `/` popover, as on cursor.com/agents: typing `/` under the cursor lists the commands and skills the
 * chat can lead with, narrowed by what follows the slash, and a tap completes the token. It floats over the composer's
 * footer from the text field it is anchored to and, unlike the "+" menu, takes no focus, so the keyboard stays up and
 * typing carries on narrowing the list until it is empty or the token is left.
 */
@Composable
fun SlashCommandPopover(
    token: SlashToken?,
    catalog: SlashCatalog,
    recent: List<String>,
    onPick: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val results = if (token == null) emptyList() else catalog.search(token.query, recent)
    val expanded = token != null && results.isNotEmpty()
    // The popover has nothing to say once the token matches nothing (a made-up word after a slash, "/2x"); it closes
    // itself rather than hanging on to an empty card.
    LaunchedEffect(token, results.isEmpty()) { if (token != null && results.isEmpty()) onDismiss() }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 4.dp),
        shape = CursorTheme.shapes.lg,
        containerColor = colors.elevated,
        border = BorderStroke(CursorDimens.hairline, colors.stroke),
        properties = PopupProperties(focusable = false),
        modifier = Modifier.semantics { contentDescription = "Slash commands" },
    ) {
        Column(Modifier.width(PopoverWidth).heightIn(max = PopoverMaxHeight).verticalScroll(rememberScrollState())) {
            results.forEach { entry ->
                SlashCommandRow(entry, onClick = { onPick(entry) })
            }
        }
        if (catalog.pending) {
            Row(Modifier.width(PopoverWidth).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 10.dp)
                Spacer(Modifier.width(6.dp))
                Text("Looking for the agent's own skills…", style = type.small, color = colors.textQuaternary, maxLines = 1)
            }
        }
    }
}

/** One suggestion: `/name` with its argument hint, and the description (or origin) beneath. */
@Composable
private fun SlashCommandRow(entry: SlashCommand, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, RectangleShape)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.command, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.argumentHint?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = type.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(entry.summary, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        commandGlyph(entry)?.let {
            Spacer(Modifier.width(8.dp))
            Icon(it, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
        }
    }
}

/** Commands get a glyph on the right — the goal's target, multitask's grid, a terminal for a machine command; skills none, as on the Skills page. */
private fun commandGlyph(entry: SlashCommand) = when {
    entry.kind != SlashCommand.Kind.Command -> null
    entry.name == "goal" -> CursorIcons.Target
    entry.name == SlashCommands.MULTITASK -> CursorIcons.Multitask
    else -> CursorIcons.Terminal
}

private val PopoverWidth = 300.dp
private val PopoverMaxHeight = 264.dp
