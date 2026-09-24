package com.cursorforandroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The one row every Settings surface is made of — the list, the account sheet, the debug sheet: a title, at most one
 * line under it, an optional glyph in front and one control or glyph at the end, [RowInset] in from the card's sides.
 * 44dp on one line, 56dp with a description, whatever the row does. Rows sit in a [SettingsCard] with a full-width
 * hairline between each two; the press is square, and the card's own clip rounds the first and last row, so a
 * highlight never draws a corner the card does not have.
 *
 * At the end: a chevron for what opens inside the app, the external-link glyph for what leaves it (a browser, the
 * system's settings, the share sheet), a switch, a check, a button or a value. Not [enabled], the row keeps its words
 * dimmed and takes no taps; its description is then the reason. [singleLine] is for words the row does not choose —
 * a name, an address, a release's first line — which end in an ellipsis rather than wrap.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    singleLine: Boolean = false,
    titleColor: Color = CursorTheme.colors.textPrimary,
    descriptionColor: Color = CursorTheme.colors.textTertiary,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val press = if (onClick != null) Modifier.pressable(onClick, RectangleShape, enabled = enabled, role = role) else Modifier
    val maxLines = if (singleLine) 1 else Int.MAX_VALUE
    Row(
        modifier.fillMaxWidth().then(press).heightIn(min = CursorDimens.listRow).padding(horizontal = RowInset, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = if (enabled) titleColor else colors.textTertiary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(description, style = type.small, color = if (enabled) descriptionColor else colors.textQuaternary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A setting that is a switch: the whole row flips it, the switch at the end says which way it is, and the flip is felt
 * as the switch's own toggle haptic. Without [felt] the row plays nothing itself, for a switch that decides its own.
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    toggleModifier: Modifier = Modifier,
    felt: Boolean = true,
) {
    val haptics = rememberHaptics()
    SettingsRow(
        title = title,
        description = description,
        modifier = modifier,
        onClick = {
            if (felt) haptics.toggle(!checked)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        trailing = { CursorToggle(checked = checked, onCheckedChange = onCheckedChange, modifier = toggleModifier, enabled = enabled, felt = felt) },
    )
}

/** The glyph at either end of a row: 16dp, in the quietest icon tone unless it carries meaning of its own. */
@Composable
internal fun RowGlyph(icon: ImageVector, tint: Color = CursorTheme.colors.iconQuaternary) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
}

/** A card of [SettingsRow]s: the elevated surface, as wide as the column it sits in (`Modifier.contentColumn` on the list's). */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CursorCard(modifier.fillMaxWidth(), content = content)
}

/** A group label over a card: 12sp at 60 %, sentence case, a category rather than the name of a row beneath it. */
@Composable
internal fun Group(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp))
}

/** Something the system has to allow before a setting can work: the warning glyph, what to allow, and the way there. */
@Composable
internal fun HintRow(title: String, description: String, onClick: () -> Unit) {
    SettingsRow(
        title = title,
        description = description,
        onClick = onClick,
        leading = { RowGlyph(CursorIcons.Warning, tint = CursorTheme.colors.orange) },
        trailing = { RowGlyph(CursorIcons.ExternalLink) },
    )
}

@Composable
internal fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    SettingsRow(title = label, onClick = { open(url) }, trailing = { RowGlyph(CursorIcons.ExternalLink) })
}

@Composable
internal fun InfoRow(label: String, value: String) {
    SettingsRow(
        title = label,
        trailing = { Text(value, style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** How far a row's words and glyphs stand in from the card's sides. */
internal val RowInset = 14.dp
