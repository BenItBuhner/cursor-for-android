package com.cursorforandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import com.cursorforandroid.domain.SlashCommands

/**
 * How a `/command` in the reader's own words is set apart from the request, wherever those words are drawn: the
 * token in the Plan pill's tint, the rest of the text as it is. The composer paints its field this way (see
 * `slashCommandHighlight` there), and so do the message bubbles of the transcript — a prompt sent from here and
 * still pending among them — the queued cards over the composer, and the account queue's editor, so a message
 * never reads differently in the bubble than it did in the field. What counts as a command is
 * [SlashCommands.tokenRanges], the one rule the composer paints by: a standalone `/name` token anywhere in the text,
 * whatever the catalog knows of it.
 */

/**
 * The tint: the Plan pill's ([pillTint] of [ModePills.Pill.Plan]) in the theme in force — the amber `charts.yellow`
 * of Cursor Dark on the dark and OLED themes, its deepened counterpart on the light — so a command and the mode
 * pill a command can become read as one thing, and the shade moves with the pill's if the pill's ever does.
 */
@Composable
fun slashCommandTint(): Color = pillTint(ModePills.Pill.Plan)

/** [text] with each of its `/command` tokens in [tint] and nothing else styled, for a plain `Text`. */
fun highlightSlashCommands(text: String, tint: Color): AnnotatedString {
    val tokens = SlashCommands.tokenRanges(text)
    if (tokens.isEmpty()) return AnnotatedString(text)
    val command = SpanStyle(color = tint)
    return buildAnnotatedString {
        var at = 0
        for (token in tokens) {
            append(text, at, token.first)
            withStyle(command) { append(text, token.first, token.last + 1) }
            at = token.last + 1
        }
        append(text, at, text.length)
    }
}

/**
 * The same for a `TextFieldValue` field, such as the account queue's editor: the tokens coloured as they are typed,
 * no character moved — so the caret, the selection and the IME see the text exactly as it is.
 */
data class SlashCommandVisualTransformation(val tint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightSlashCommands(text.text, tint), OffsetMapping.Identity)
}
