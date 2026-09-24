package com.cursorforandroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.shortcuts.ShortcutLineRow
import com.cursorforandroid.ui.shortcuts.ShortcutsCopy
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

object KeyboardShortcutsTags {
    const val PAGE = "keyboard_shortcuts_page"
}

/**
 * Settings › Keyboard shortcuts: every chord the app answers from a hardware keyboard, in the groups the cheat sheet
 * (Ctrl+/) shows them in, from the same copy ([ShortcutsCopy]).
 */
@Composable
fun KeyboardShortcutsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxSize().background(colors.canvas).testTag(KeyboardShortcutsTags.PAGE)) {
        CursorHeader(
            title = ShortcutsCopy.TITLE,
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .navigationBarsPadding()
                .fadingVerticalScroll(surface = colors.canvas)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A reading column, centred on a wide pane.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(ShortcutsCopy.HARDWARE_ONLY, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
                ShortcutsCopy.groups.forEach { group ->
                    Group(group.title)
                    SettingsCard {
                        group.lines.forEachIndexed { index, line ->
                            if (index > 0) HairlineDivider()
                            ShortcutLineRow(line, Modifier.heightIn(min = CursorDimens.listRow).padding(horizontal = RowInset, vertical = 11.dp))
                        }
                    }
                }
                Text(ShortcutsCopy.TEXT_EDITING, style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
            }
        }
    }
}
