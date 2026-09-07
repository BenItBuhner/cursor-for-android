package com.cursorforandroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (20 % focused),
 * radius 12, 12px padding, 14/22 text, and a footer of round buttons — "+" (attach) on the left, send / stop on
 * the right — with the 13px model selector next to the "+".
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
    onPlus: (() -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: ((PendingAttachment) -> Unit)? = null,
    modelLabel: String? = null,
    onModel: (() -> Unit)? = null,
    footerExtra: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
    focusRequester: FocusRequester? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.xl
    var focused by remember { mutableStateOf(false) }
    val pad = CursorDimens.composerPadding
    val border by animateColorAsState(if (focused) colors.strokeStrong else colors.strokeSubtle, tween(160), label = "border")

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
            value = value,
            onValueChange = onValueChange,
            textStyle = type.input.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            minLines = minLines,
            maxLines = 10,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = type.input, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(CursorDimens.roundButton), verticalAlignment = Alignment.CenterVertically) {
            if (onPlus != null) {
                ComposerRoundButton(CursorIcons.Plus, "Attach image", onClick = onPlus)
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
            if (isRunning && onStop != null && !canSend) {
                ComposerRoundButton(CursorIcons.Stop, "Stop", onClick = onStop, prominent = true)
            } else {
                ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = onSend, prominent = canSend, enabled = canSend)
            }
        }
    }
}

/**
 * Plain-text selector with a small chevron: "codex-poly-bot ⌄", "main ⌄", "Claude Fable 5.1 1M Max ⌄". Nothing is
 * painted around it until pressed; a chip that cannot be changed drops the chevron instead of greying out.
 */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = enabled,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            .heightIn(min = 28.dp)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(15.dp))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = if (mono) type.code.copy(fontSize = type.base.fontSize) else type.base,
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
