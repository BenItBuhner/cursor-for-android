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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Cursor's prompt box: `--cursor-editor` surface, 8 % stroke (12 % when focused), radius 12, 14/22 text, and a
 * footer holding the round "+" button, the plain-text model selector and the mic / send / stop control.
 * Context selectors (repo, branch, environment) sit above the box on the home pane — see [SelectorRow].
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
    modelLabel: String? = null,
    onModel: (() -> Unit)? = null,
    footerExtra: (@Composable RowScope.() -> Unit)? = null,
    minLines: Int = 1,
    focusRequester: FocusRequester? = null,
    enableSpeech: Boolean = true,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.xl
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        onValueChange(if (value.isBlank()) spoken else "$value $spoken")
    }
    val speechAvailable = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null }

    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, if (focused) colors.stroke else colors.strokeSubtle, shape)
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = type.message.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            minLines = minLines,
            maxLines = 10,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = type.message, color = colors.textTertiary)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onPlus != null) {
                ComposerRoundButton(CursorIcons.Plus, "Add context", onClick = onPlus)
                Spacer(Modifier.width(10.dp))
            }
            if (modelLabel != null) {
                SelectorChip(modelLabel, onClick = onModel ?: {}, enabled = onModel != null)
            }
            footerExtra?.invoke(this)
            Spacer(Modifier.weight(1f))
            when {
                isRunning && onStop != null && !canSend -> ComposerRoundButton(CursorIcons.Stop, "Stop", onClick = onStop, prominent = true)
                canSend -> ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = onSend, prominent = true)
                enableSpeech && speechAvailable -> ComposerRoundButton(
                    CursorIcons.Mic, "Dictate",
                    onClick = {
                        speechLauncher.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            },
                        )
                    },
                    prominent = true,
                )
                else -> ComposerRoundButton(CursorIcons.ArrowUp, "Send", onClick = {}, enabled = false)
            }
        }
    }
}

/** Plain-text selector with a chevron: "codex-poly-bot ⌄", "master ⌄", "Claude Fable 5.1 1M Max ⌄". */
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
            .pressable(onClick, CursorTheme.shapes.base, enabled = enabled)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = if (mono) type.code.copy(fontSize = type.base.fontSize) else type.base,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Icon(CursorIcons.ChevronDown, null, tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
    }
}

/** The row of context selectors that sits above the home composer. */
@Composable
fun SelectorRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
