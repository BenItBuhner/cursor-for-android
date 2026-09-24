package com.cursorforandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ProjectPalette

/**
 * How a `/command` in the reader's own words is set apart from the request, wherever those words are drawn: the
 * token in its tint ([CommandTints]), the rest of the text as it is. The composer paints its field this way (see
 * `slashCommandHighlight` there), and so do the message bubbles of the transcript — a prompt sent from here and
 * still pending among them — the queued cards over the composer, and the account queue's editor, so a message
 * never reads differently in the bubble than it did in the field. What counts as a command is
 * [SlashCommands.tokenRanges], the one rule the composer paints by: a standalone `/name` token anywhere in the text,
 * whatever the catalog knows of it.
 */

/**
 * The colours of the `/command` tokens. A command is the desktop's command chip (3.21.18 `command-chip-shared.js`,
 * `ui-prompt-input-command-chip`: `color: var(--cursor-text-yellow-primary)` over no background, in the prompt and in
 * the sent message alike), the glass palette's yellow: [command]. A mode's own token — `/plan`, `/debug`,
 * `/multitask`, `/ask` — is painted in the tint of the pill it stands for instead ([modes], by name), so a
 * `/multitask` in a sent message reads in the Multitask pill's purple and a `/debug` in Debug's red.
 */
@Immutable
data class CommandTints(val command: Color, val modes: Map<String, Color>) {
    /** The colour of the token `/[name]`. */
    fun forToken(name: String): Color = modes[name] ?: command

    /** The same tints at [alpha] of their strength, for text drawn faded. */
    fun faded(alpha: Float): CommandTints =
        if (alpha >= 1f) this else CommandTints(command.copy(alpha = command.alpha * alpha), modes.mapValues { (_, c) -> c.copy(alpha = c.alpha * alpha) })

    companion object {
        private fun of(dark: Boolean) = CommandTints(
            command = checkNotNull(ProjectPalette.color("yellow", dark)),
            modes = ModePills.Pill.entries.associate { it.command to pillTint(it, dark) },
        )

        private val Dark = of(dark = true)
        private val Light = of(dark = false)

        /** The tints of a dark (and OLED) or a light theme. */
        fun forTheme(dark: Boolean): CommandTints = if (dark) Dark else Light
    }
}

/** The [CommandTints] of the theme in force. */
@Composable
fun commandTints(): CommandTints = CommandTints.forTheme(CursorTheme.colors.isDark)

/** [text] with each of its `/command` tokens in its tint and nothing else styled, for a plain `Text`. */
fun highlightSlashCommands(text: String, tints: CommandTints): AnnotatedString {
    val tokens = SlashCommands.tokenRanges(text)
    if (tokens.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (token in tokens) {
            append(text, at, token.first)
            withStyle(SpanStyle(color = tints.forToken(text.substring(token.first + 1, token.last + 1)))) { append(text, token.first, token.last + 1) }
            at = token.last + 1
        }
        append(text, at, text.length)
    }
}

/**
 * The same for a `TextFieldValue` field, such as the account queue's editor: the tokens coloured as they are typed,
 * no character moved — so the caret, the selection and the IME see the text exactly as it is.
 */
data class SlashCommandVisualTransformation(val tints: CommandTints) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightSlashCommands(text.text, tints), OffsetMapping.Identity)
}
