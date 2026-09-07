package com.cursorforandroid.ui.components

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
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Cursor's prompt box as measured on cursor.com/agents: `--cursor-editor` surface, 8 % stroke (12 % focused),
 * radius 12, 12px padding, 14/22 text, and a footer of 24px round buttons — "+" on the left, opening the
 * Multitask / Files / Skills / MCP Servers menu ([ComposerPlusMenu]), send / stop on the right — with the 13px
 * model selector next to the "+".
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
    /** Shows the "+" button and backs its menu; null hides the button. */
    plusMenu: ComposerMenuActions? = null,
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

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, if (focused) colors.stroke else colors.strokeSubtle, shape)
            .padding(start = pad, end = pad, top = pad, bottom = pad),
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
                    if (value.isEmpty()) Text(placeholder, style = type.input, color = colors.textTertiary)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(CursorDimens.roundButton), verticalAlignment = Alignment.CenterVertically) {
            if (plusMenu != null) {
                var menuOpen by remember { mutableStateOf(false) }
                // The Box is the anchor: the menu drops from the "+" like the web's popover.
                Box {
                    ComposerRoundButton(CursorIcons.Plus, "Add to prompt", onClick = { menuOpen = true })
                    ComposerPlusMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        prompt = value,
                        onPromptChange = onValueChange,
                        actions = plusMenu,
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
            if (modelLabel != null) {
                SelectorChip(modelLabel, onClick = onModel ?: {}, enabled = onModel != null)
            }
            footerExtra?.invoke(this)
            Spacer(Modifier.weight(1f))
            if (isRunning && onStop != null && !canSend) {
                ComposerRoundButton(CursorIcons.Stop, "Stop", onClick = onStop, prominent = true)
            } else {
                ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = onSend, prominent = canSend, enabled = canSend)
            }
        }
    }
}

/** Plain-text selector with an 8px chevron: "codex-poly-bot ⌄", "master ⌄", "Claude Fable 5.1 1M Max ⌄". */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .pressable(onClick, CursorTheme.shapes.sm, enabled = enabled)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = if (mono) type.code.copy(fontSize = type.base.fontSize) else type.base,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        Icon(CursorIcons.ChevronDown, null, tint = colors.iconTertiary, modifier = Modifier.size(CursorDimens.chevron))
    }
}

/** The row of context selectors that sits 12px above the home composer. */
@Composable
fun SelectorRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
