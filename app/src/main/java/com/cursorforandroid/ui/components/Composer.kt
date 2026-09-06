package com.cursorforandroid.ui.components

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The prompt box shared by New Agent and follow-ups: level-1 surface, hairline border that brightens on
 * focus, optional context chips above the text, and the + / model / mic / send footer.
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
    header: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
    focusRequester: FocusRequester? = null,
    enableSpeech: Boolean = true,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.bubble
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        onValueChange(if (value.isBlank()) spoken else "$value $spoken")
    }
    val speechAvailable = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.surface, if (focused) colors.borderFocus else colors.borderSubtle, shape)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
    ) {
        if (header != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = header)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = CursorTheme.typography.message.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            minLines = minLines,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = CursorTheme.typography.message, color = colors.textPlaceholder)
                    inner()
                }
            },
        )
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CursorIconButton(
                Icons.Outlined.Add, "More options",
                onClick = { onPlus?.invoke() },
                size = 32.dp, iconSize = 18.dp, filled = true, tint = colors.textSecondary, enabled = onPlus != null,
            )
            if (footer != null) {
                Spacer(Modifier.size(8.dp))
                footer()
            }
            Spacer(Modifier.weight(1f))
            if (enableSpeech && speechAvailable) {
                CursorIconButton(
                    Icons.Outlined.Mic, "Dictate",
                    onClick = {
                        speechLauncher.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your prompt")
                            },
                        )
                    },
                    size = 32.dp, iconSize = 18.dp, filled = true, tint = colors.textSecondary,
                )
                Spacer(Modifier.size(8.dp))
            }
            if (isRunning && onStop != null && !canSend) {
                CursorIconButton(Icons.Outlined.Stop, "Stop run", onClick = onStop, size = 32.dp, iconSize = 16.dp, prominent = true)
            } else {
                CursorIconButton(
                    Icons.Outlined.ArrowUpward, "Send",
                    onClick = onSend, size = 32.dp, iconSize = 18.dp,
                    prominent = canSend, filled = true, tint = colors.textPlaceholder, enabled = canSend,
                )
            }
        }
    }
}

/** Small selector chip used in the composer header: label + chevron ("cursor-for-android ⌄", "main ⌄", "Cloud ⌄"). */
@Composable
fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    mono: Boolean = false,
) {
    val colors = CursorTheme.colors
    Row(
        modifier.pressable(onClick, CursorTheme.shapes.sm).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = if (mono) CursorTheme.typography.code.copy(fontSize = CursorTheme.typography.secondary.fontSize) else CursorTheme.typography.secondary,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Icon(Icons.Outlined.ExpandMore, null, tint = colors.textPlaceholder, modifier = Modifier.size(14.dp))
    }
}
