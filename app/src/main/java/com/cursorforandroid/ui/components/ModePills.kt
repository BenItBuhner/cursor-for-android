package com.cursorforandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The two slash commands the composer wears as pills instead of text, as cursor.com/agents does: `/multitask`, which
 * still travels in the prompt as its token, and `/plan`, which is plan mode (`mode: "plan"` on the run) and never
 * travels as text at all.
 *
 * Everything here is presentation. The owner of the composer keeps holding the prompt the way it has always been
 * sent — `/multitask fix the flaky test` — and the field shows `fix the flaky test` beside a Multitask pill; every
 * keystroke hands the owner the text with the token back in front ([compose]). Only these two become pills: `/goal`,
 * a skill or a machine command stay in the text, painted as commands.
 */
object ModePills {
    /** A pill: the slash command it stands for, and the word on it. */
    enum class Pill(val command: String, val label: String) {
        Multitask(SlashCommands.MULTITASK, "Multitask"),
        Plan(SlashCommands.PLAN, "Plan"),
    }

    /** What the field shows of the owner's value, and whether the Multitask pill is on. */
    data class Presented(val text: String, val multitask: Boolean)

    /**
     * [value] with its first closed `/multitask` token — one with whitespace after it, as [compose] and the "+" menu
     * write it — and that whitespace taken out. A `/multitask` ending the text is still being typed: it stays text,
     * under the popover, until a space or a pick closes it (see [consumeTyped]).
     */
    fun present(value: String): Presented {
        val span = CLOSED_MULTITASK.find(value)?.range ?: return Presented(value, multitask = false)
        return Presented(without(value, span), multitask = true)
    }

    /** The field's [text] in the owner's shape: led by `/multitask ` while the pill is on, so the request carries it. */
    fun compose(text: String, multitask: Boolean): String = if (multitask) "/${SlashCommands.MULTITASK} $text" else text

    /** Which pill a picked or typed command becomes, if any: `/plan` only where plan mode can be set ([planEnabled]). */
    fun pillFor(name: String, planEnabled: Boolean): Pill? = when (name) {
        SlashCommands.MULTITASK -> Pill.Multitask
        SlashCommands.PLAN -> if (planEnabled) Pill.Plan else null
        else -> null
    }

    /** The field's text once commands typed into it have become pills, the caret kept on the same characters. */
    data class Typed(val text: String, val selection: TextRange, val pills: Set<Pill>)

    /**
     * Takes every `/multitask ` and (with [planEnabled]) `/plan ` — a token with whitespace after it, so the reader
     * has closed it — out of [text]. A token still being typed at the end of the text stays, so the popover can
     * finish it, and a tap on Send with one there sends it as text, as it always has.
     */
    fun consumeTyped(text: String, selection: TextRange, planEnabled: Boolean): Typed {
        var result = text
        var start = selection.start
        var end = selection.end
        val pills = mutableSetOf<Pill>()
        // Later tokens first, so the spans of the earlier ones stay valid as characters leave.
        for (match in CLOSED.findAll(text).toList().asReversed()) {
            val pill = pillFor(match.groupValues[1], planEnabled) ?: continue
            pills += pill
            // The token and the whitespace that closed it.
            val removed = match.range.first..match.range.last + 1
            result = result.removeRange(removed)
            start = shift(start, removed)
            end = shift(end, removed)
        }
        return Typed(result, TextRange(start, end), pills)
    }

    /**
     * The field's text once the `/` token under the cursor has been picked as a pill from the popover: the token
     * gone with the space that followed it, the caret where it stood.
     */
    fun consumeToken(text: String, token: SlashToken): TextFieldValue {
        val next = without(text, token.start until token.end)
        return TextFieldValue(next, TextRange(minOf(token.start, next.length)))
    }

    private val CLOSED = Regex("(?<=^|\\s)/(${SlashCommands.MULTITASK}|${SlashCommands.PLAN})(?=\\s)")
    private val CLOSED_MULTITASK = Regex("(?<=^|\\s)/${SlashCommands.MULTITASK}(?=\\s)")

    /** [index] once [removed] has left the text: unchanged before it, at its start inside it, moved up after it. */
    private fun shift(index: Int, removed: IntRange): Int = when {
        index <= removed.first -> index
        index > removed.last -> index - (removed.last - removed.first + 1)
        else -> removed.first
    }

    /** [text] without [span] and the one whitespace that separated it: the one after, else the one before. */
    private fun without(text: String, span: IntRange): String {
        val after = span.last + 1
        return when {
            after < text.length && text[after].isWhitespace() -> text.removeRange(span.first, after + 1)
            span.first > 0 && text[span.first - 1].isWhitespace() -> text.removeRange(span.first - 1, after)
            else -> text.removeRange(span.first, after)
        }
    }
}

/**
 * The violet cursor.com/agents paints its Multitask pill in, measured off the official capture: glyph, label and
 * cross at #A296EC on a 12 % wash of the same over the composer's surface (the wash reads #272532 on #181818, as
 * the reference does). Not the theme's `purple` — the Anysphere mauve, #B48EAD — which is grey at that alpha. The
 * light theme keeps the hue two steps darker so the label holds its contrast on the light surface.
 */
internal val PillVioletDark = Color(0xFFA296EC)
internal val PillVioletLight = Color(0xFF6A5ACD)

/** How much of the violet the pill's wash carries over the surface, dark or light. */
private const val PillWashAlpha = 0.12f

/**
 * A mode the message goes out under, worn in the composer footer the way cursor.com/agents wears Multitask: a wash
 * of the web's violet ([PillVioletDark] / [PillVioletLight]) with the mode's glyph and name in the same violet, and a
 * cross that takes it off again. The label never wraps and yields no width: the model chip beside send is what gives
 * way when the footer is tight.
 */
@Composable
fun ModePill(pill: ModePills.Pill, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val tint = if (colors.isDark) PillVioletDark else PillVioletLight
    Row(
        modifier
            .heightIn(min = CursorDimens.roundButton)
            .background(tint.copy(alpha = PillWashAlpha), CursorTheme.shapes.base)
            .padding(start = 8.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(pill.icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(pill.label, style = type.base, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(3.dp))
        TouchTarget(size = 18.dp, touchSize = 32.dp, shape = CircleShape, onClick = onClear) {
            Icon(CursorIcons.Close, "Remove ${pill.label}", tint = tint, modifier = Modifier.size(12.dp))
        }
    }
}

private val ModePills.Pill.icon: ImageVector
    get() = when (this) {
        ModePills.Pill.Multitask -> CursorIcons.Multitask
        ModePills.Pill.Plan -> CursorIcons.Plan
    }
