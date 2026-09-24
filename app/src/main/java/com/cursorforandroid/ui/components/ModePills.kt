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
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The slash commands the composer wears as pills instead of text, as cursor.com/agents does: `/multitask`, which
 * still travels in the prompt as its token, and the modes — `/plan`, which is plan mode (`mode: "plan"` on the run),
 * and in Extended mode `/ask` and `/debug`, which are `agent.v1.AgentMode` ASK and DEBUG on the account's start and follow-up —
 * none of which travels as text at all. They are settings of one slot — a run is planned, or answers, or debugs, or
 * fans out to subagents, not two of those — so turning any on takes the others off, and the composer only ever wears one.
 *
 * Everything here is presentation. The owner of the composer keeps holding the prompt the way it has always been
 * sent — `/multitask fix the flaky test` — and the field shows `fix the flaky test` beside a Multitask pill; every
 * keystroke hands the owner the text with the token back in front ([compose]). Only these become pills: `/goal`, a
 * skill or a machine command stay in the text, painted as commands in the Plan pill's tint ([slashCommandTint]).
 */
object ModePills {
    /** A pill: the slash command it stands for, the word on it, and the line the desktop's mode menu gives it (3.21.18 `modeConfig.js`). */
    enum class Pill(val command: String, val label: String, val description: String) {
        Multitask(SlashCommands.MULTITASK, "Multitask", "Orchestrate multiple subagents in parallel"),
        Plan(SlashCommands.PLAN, "Plan", "Generate an implementation plan"),
        Ask(SlashCommands.ASK, "Ask", "Answer questions without making edits"),
        Debug(SlashCommands.DEBUG, "Debug", "Pinpoint the root cause of an issue"),
        ;

        /** The mode a pill asks the run for; null for Multitask, which rides in the text instead. */
        val agentMode: AgentMode?
            get() = when (this) {
                Multitask -> null
                Plan -> AgentMode.PLAN
                Ask -> AgentMode.ASK
                Debug -> AgentMode.DEBUG
            }

        /** True for the pills that need the account's follow-up to travel (Extended mode). */
        val isExtended: Boolean get() = agentMode?.needsAccountService == true

        companion object {
            /** The pill for a mode the owner holds; null for none, and for a mode the composer has no pill for. */
            fun of(mode: AgentMode?): Pill? = entries.firstOrNull { it.agentMode != null && it.agentMode == mode }

            /** The order the desktop lists the modes in, and Shift+Tab steps through them (3.21.18 `Ans`: plan, debug, multitask, chat). */
            val desktopOrder: List<Pill> = listOf(Plan, Debug, Multitask, Ask)
        }
    }

    /**
     * The mode Shift+Tab moves to from [current], as the desktop's "Cycle Mode" does: no mode, then each of
     * [desktopOrder] this composer can wear ([available]), then no mode again. A pill that is not in the cycle
     * starts it over at its first mode. Null when there is nothing to cycle through.
     */
    fun next(current: Pill?, available: (Pill) -> Boolean): Pill? {
        val cycle = listOf<Pill?>(null) + Pill.desktopOrder.filter(available)
        if (cycle.size == 1) return null
        val at = cycle.indexOf(current)
        return if (at < 0) cycle[1] else cycle[(at + 1) % cycle.size]
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

    /**
     * Which pill a picked or typed command becomes, if any: `/plan` only where a mode can be set ([planEnabled]),
     * `/ask` and `/debug` only where the account's modes are on as well ([extended]).
     */
    fun pillFor(name: String, planEnabled: Boolean, extended: Boolean = false): Pill? = when (name) {
        SlashCommands.MULTITASK -> Pill.Multitask
        SlashCommands.PLAN -> if (planEnabled) Pill.Plan else null
        SlashCommands.ASK -> if (planEnabled && extended) Pill.Ask else null
        SlashCommands.DEBUG -> if (planEnabled && extended) Pill.Debug else null
        else -> null
    }

    /**
     * The field's text once commands typed into it have become pills, the caret kept on the same characters. [pills]
     * are in the order they stood in the text; the last is the one that ends up on, the modes being one slot.
     */
    data class Typed(val text: String, val selection: TextRange, val pills: List<Pill>) {
        /** The pill left on once the typed ones have replaced each other in turn, if any was typed. */
        val turnedOn: Pill? get() = pills.lastOrNull()
    }

    /**
     * Takes every `/multitask ` and (with [planEnabled]) `/plan ` — and, with [extended] too, `/ask ` and `/debug ` —
     * a token with whitespace after it, so the reader has closed it — out of [text]. A token still being typed at the
     * end of the text stays, so the popover can finish it, and a tap on Send with one there sends it as text, as it always has.
     */
    fun consumeTyped(text: String, selection: TextRange, planEnabled: Boolean, extended: Boolean = false): Typed {
        var result = text
        var start = selection.start
        var end = selection.end
        val pills = ArrayList<Pill>()
        // Later tokens first, so the spans of the earlier ones stay valid as characters leave.
        for (match in CLOSED.findAll(text).toList().asReversed()) {
            val pill = pillFor(match.groupValues[1], planEnabled, extended) ?: continue
            pills += pill
            // The token and the whitespace that closed it.
            val removed = match.range.first..match.range.last + 1
            result = result.removeRange(removed)
            start = shift(start, removed)
            end = shift(end, removed)
        }
        return Typed(result, TextRange(start, end), pills.asReversed())
    }

    /**
     * The field's text once the `/` token under the cursor has been picked as a pill from the popover: the token
     * gone with the space that followed it, the caret where it stood.
     */
    fun consumeToken(text: String, token: SlashToken): TextFieldValue {
        val next = without(text, token.start until token.end)
        return TextFieldValue(next, TextRange(minOf(token.start, next.length)))
    }

    private val CLOSED = Regex("(?<=^|\\s)/(${SlashCommands.MULTITASK}|${SlashCommands.PLAN}|${SlashCommands.ASK}|${SlashCommands.DEBUG})(?=\\s)")
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

/*
 * The desktop's own colour for each mode (3.21.18 `modeConfig.js`: Plan `yellow`, Debug `red`, Ask `green`, Multitask
 * `purple`), as its glass themes define those tokens: `cursor-dark` and `cursor-light` in OKLCH, which come out in sRGB
 * as the Cursor Dark and Cursor Light themes' `charts.yellow`, `terminal.ansiRed`, `terminal.ansiGreen` and
 * `charts.purple`. The `/commands` in the reader's text are painted in Plan's ([slashCommandTint]), so a command and
 * the pill a command can become read as one thing.
 */
internal val PillPurpleDark = Color(0xFF9386F2)
internal val PillPurpleLight = Color(0xFF7565CC)
internal val PillYellowDark = Color(0xFFF1B467)
internal val PillYellowLight = Color(0xFFA46700)
internal val PillGreenDark = Color(0xFF3FA266)
internal val PillGreenLight = Color(0xFF007041)
internal val PillRedDark = Color(0xFFFC6B83)
internal val PillRedLight = Color(0xFFBE1744)

/** How much of its tint a pill's wash carries over the surface, dark or light: the desktop's `color-mix(… 12%, transparent)`. */
private const val PillWashAlpha = 0.12f

/** A pill's tint in the theme in force: Multitask purple, Plan yellow, Ask green, Debug red, as the desktop paints them. */
@Composable
internal fun pillTint(pill: ModePills.Pill): Color = pillTint(pill, CursorTheme.colors.isDark)

internal fun pillTint(pill: ModePills.Pill, dark: Boolean): Color = when (pill) {
    ModePills.Pill.Multitask -> if (dark) PillPurpleDark else PillPurpleLight
    ModePills.Pill.Plan -> if (dark) PillYellowDark else PillYellowLight
    ModePills.Pill.Ask -> if (dark) PillGreenDark else PillGreenLight
    ModePills.Pill.Debug -> if (dark) PillRedDark else PillRedLight
}

/**
 * A mode the message goes out under, worn in the composer footer the way cursor.com/agents wears Multitask: a
 * stadium of the mode's tint at a wash ([pillTint]) with its glyph and name in the same tint, and a cross that takes
 * it off again. The label never wraps and yields no width: the model chip beside send is what gives way when the
 * footer is tight.
 */
@Composable
fun ModePill(pill: ModePills.Pill, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val type = CursorTheme.typography
    val tint = pillTint(pill)
    Row(
        modifier
            .heightIn(min = CursorDimens.roundButton)
            .background(tint.copy(alpha = PillWashAlpha), CursorTheme.shapes.full)
            .padding(start = 10.dp, end = 4.dp),
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

internal val ModePills.Pill.icon: ImageVector
    get() = when (this) {
        ModePills.Pill.Multitask -> CursorIcons.Multitask
        ModePills.Pill.Plan -> CursorIcons.Plan
        ModePills.Pill.Ask -> CursorIcons.Ask
        ModePills.Pill.Debug -> CursorIcons.Bug
    }
