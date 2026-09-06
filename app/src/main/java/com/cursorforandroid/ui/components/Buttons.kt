package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Cursor buttons. Ghost (default): 4% wash + hairline border. Primary: accent blue with near-black label.
 * Destructive: ghost with danger label.
 */
@Composable
fun CursorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.md
    val fill = when {
        primary -> if (enabled) colors.accentBlue else colors.accentBlue.copy(alpha = 0.35f)
        else -> colors.wash
    }
    val border = if (primary) Color.Transparent else colors.borderSubtle
    val label = when {
        primary -> colors.onAccent
        destructive -> colors.danger
        enabled -> colors.textPrimary
        else -> colors.textPlaceholder
    }
    Row(
        modifier
            .cursorSurface(fill, border, shape)
            .pressable(onClick, shape, enabled = enabled)
            .height(42.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 8.dp))
        }
        Text(text, style = CursorTheme.typography.bodyMedium, color = label)
    }
}
