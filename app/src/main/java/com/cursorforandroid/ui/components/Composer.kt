package com.cursorforandroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (20 % focused),
 * radius 12, 12px padding, 14/22 text, and a footer of round buttons — "+" on the left, opening the
 * Multitask / Files / Skills / MCP Servers menu ([ComposerPlusMenu]), send / stop on the right — with the 13px
 * model selector next to the "+". The text is the largest thing in the box and the round buttons the smallest
 * controls ([CursorDimens.roundButton] beside [CursorTypography.input]), as on the web; the chips sit in between.
 *
 * While [isSending] the send slot shows a busy ring; once the request has been in flight for a moment it turns into
 * a Stop button that calls [onCancelSend] (when given), so a launch that drags on can be abandoned without a
 * nervous double-tap cancelling one that is about to succeed.
 */
@Composable
fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    canSend: Boolean = value.isNotBlank(),
    isRunning: Boolean = false,
    onStop: (() -> Unit)? = null,
    isSending: Boolean = false,
    onCancelSend: (() -> Unit)? = null,
    /** Shows the "+" button and backs its menu; null hides the button. */
    plusMenu: ComposerMenuActions? = null,
    attachments: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: ((PendingAttachment) -> Unit)? = null,
    modelLabel: String? = null,
    onModel: (() -> Unit)? = null,
    footerExtra: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.xl
    // Saved alongside the text and the caret, so a composer rebuilt from instance state is left the way the reader
    // had it. The want is taken from it once, before the field has reported its own state over the top; a field that
    // was not focused is never given focus, since arriving on a screen must not throw the keyboard up.
    var focused by rememberSaveable(saver = FocusedSaver) { mutableStateOf(false) }
    var wantsFocus by remember { mutableStateOf(focused) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // Waits for the "+" menu to be gone: its popup holds focus while it is up, and a request made under it is lost.
    LaunchedEffect(wantsFocus, menuOpen) {
        if (wantsFocus && !menuOpen) {
            wantsFocus = false
            focusRequester.requestFocus()
        }
    }
    var cancelOffered by remember { mutableStateOf(false) }
    LaunchedEffect(isSending, onCancelSend != null) {
        cancelOffered = false
        if (isSending && onCancelSend != null) {
            delay(CancelOfferDelayMillis)
            cancelOffered = true
        }
    }
    val pad = CursorDimens.composerPadding
    val border by animateColorAsState(if (focused) colors.strokeStrong else colors.strokeSubtle, tween(160), label = "border")
    // The field owns the text and the selection; [value] only says what the owner last made of it. Comparing the two
    // directly would mean rewriting the field whenever they disagree, which is wrong while they are meant to: the
    // owner may answer onValueChange a frame late — a debounce, a trim, a length cap, a flow — and the rewrite would
    // put the stale text back and throw the caret to the end mid-word. So what is tracked is the last value that came
    // from outside, and only a change in that is adopted, with the cursor at the end so typing continues after a
    // slash command from the "+" menu instead of wherever the cursor happened to be. Both are saved: the field keeps
    // its caret across a rotation, and adopted keeps a restored draft from outliving the owner that cleared it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var adopted by rememberSaveable { mutableStateOf(value) }
    if (value != adopted) {
        adopted = value
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, border, shape)
            .padding(start = pad, end = pad, top = pad, bottom = pad - 2.dp),
    ) {
        if (attachments.isNotEmpty() && onRemoveAttachment != null) {
            AttachmentStrip(attachments, onRemoveAttachment)
        }
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                if (next.text != value) onValueChange(next.text)
            },
            textStyle = type.input.copy(color = colors.textPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            cursorBrush = SolidColor(colors.textPrimary),
            minLines = minLines,
            maxLines = 10,
            modifier = Modifier
                .fillMaxWidth()
                // One line of `input` at the default font scale, so the box does not shrink under a small system font.
                .heightIn(min = 22.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) Text(placeholder, style = type.input, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        // The footer's designed height is a minimum: the model chip and anything [footerExtra] adds are sp-sized, and
        // an exact constraint here would hold them to 28dp however much taller they asked to be.
        Row(Modifier.fillMaxWidth().heightIn(min = CursorDimens.composerFooter), verticalAlignment = Alignment.CenterVertically) {
            if (plusMenu != null) {
                // The Box is the anchor: the menu drops from the "+" like the web's popover.
                Box {
                    ComposerRoundButton(CursorIcons.Plus, "Add to prompt", onClick = { menuOpen = true })
                    ComposerPlusMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        prompt = value,
                        onPromptChange = { next ->
                            onValueChange(next)
                            // The command goes in at the front of the prompt and the caret follows the adopted text
                            // to the end, which is where the reader carries on writing; the field is handed back with it.
                            wantsFocus = true
                        },
                        actions = plusMenu,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            // The model chip takes what it needs and ellipsises only when the trailing buttons would otherwise be pushed out.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (modelLabel != null) {
                    SelectorChip(modelLabel, onClick = onModel ?: {}, enabled = onModel != null, showChevron = onModel != null, modifier = Modifier.weight(1f, fill = false))
                }
                footerExtra?.invoke(this)
            }
            Spacer(Modifier.width(8.dp))
            when {
                isSending && cancelOffered && onCancelSend != null -> ComposerRoundButton(CursorIcons.Stop, "Cancel sending", onClick = onCancelSend, prominent = true)
                isSending -> ComposerBusyButton()
                isRunning && onStop != null && !canSend -> ComposerRoundButton(CursorIcons.Stop, "Stop", onClick = onStop, prominent = true)
                else -> ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = onSend, prominent = canSend, enabled = canSend)
            }
        }
    }
}

/** The send slot while a prompt is in flight: the prominent disc with a canvas-coloured ring instead of a glyph. */
@Composable
private fun ComposerBusyButton(modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Box(
        modifier
            .size(CursorDimens.roundButton)
            .background(colors.textPrimary, CircleShape)
            .semantics { contentDescription = "Sending" },
        contentAlignment = Alignment.Center,
    ) {
        SpinnerRing(color = colors.canvas, size = CursorDimens.roundButtonGlyph, strokeWidth = 1.5.dp)
    }
}

/** How long a send has to be in flight before the busy ring becomes a cancel button. */
private const val CancelOfferDelayMillis = 2_500L

/**
 * Saves whether the field had focus, rather than the state it is held in. `rememberSaveable { mutableStateOf(…) }`
 * puts the holder itself into the saved state, and the field is blurred as the composition comes down — after the
 * save has been taken but while the holder is still the live one, which turns a focused composer into an unfocused
 * one on the way back.
 */
private val FocusedSaver = Saver<MutableState<Boolean>, Boolean>(save = { it.value }, restore = { mutableStateOf(it) })

/**
 * Plain-text selector with a small chevron: "codex-poly-bot ⌄", "main ⌄", "Claude Fable 5.1 ⌄". Nothing is
 * painted around it until pressed; a chip that cannot be changed drops the chevron instead of greying out.
 */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = enabled,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            // The composer footer's height: a fair tap height for a chip that paints nothing until pressed.
            .heightIn(min = CursorDimens.composerFooter)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(15.dp))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = type.base,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) Icon(CursorIcons.ChevronDown, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
    }
}

/** The row of context selectors that sits above the home composer. */
@Composable
fun SelectorRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}
