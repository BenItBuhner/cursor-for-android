package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Compact desktop-style header: 44dp, flat icon buttons, start-aligned 14sp medium title. No centred nav-bar
 * title, no circular buttons.
 */
@Composable
fun CursorHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(CursorDimens.headerHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        if (title != null) {
            Spacer(Modifier.width(if (leading != null) 4.dp else 8.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title, style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke(this)
    }
}
