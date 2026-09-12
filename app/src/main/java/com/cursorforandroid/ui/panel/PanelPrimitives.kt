package com.cursorforandroid.ui.panel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A section's header: its glyph, its title (a heading, for TalkBack's heading navigation), a hint in the tertiary
 * colour at the end, and a chevron that turns down when the section is open. The whole row toggles it.
 */
@Composable
internal fun SectionHeader(section: PanelSection, hint: String?, expanded: Boolean, onToggle: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onToggle, CursorTheme.shapes.base)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("section-${section.id.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(section.title, style = type.baseMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        if (hint != null) {
            Text(hint, style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 6.dp).weight(2f, fill = false))
        }
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
    }
}

/** One row of a section's list: a glyph, a title, a dimmed detail under or beside it, and something at the end. */
@Composable
internal fun PanelRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = CursorTheme.colors.iconTertiary,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, CursorTheme.shapes.base) else Modifier)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A key on the left, its value on the right: the Overview's facts. */
@Composable
internal fun FactRow(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick, CursorTheme.shapes.base) else Modifier)
            .heightIn(min = 30.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.base, color = colors.textTertiary, modifier = Modifier.width(96.dp))
        Text(value, style = type.base.copy(fontFeatureSettings = "tnum"), color = if (onClick != null) colors.link else colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** A line of explanation in the section's body, dimmed. */
@Composable
internal fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
}

/** A small caption above a group of rows: "From the pull request", "Checks". */
@Composable
internal fun PanelCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() })
}

/**
 * The named states every section degrades to instead of a blank: waiting on a read, a read that failed (with a
 * retry when one can help), nothing to show for this chat, a surface Extended mode gates, and a host the browser has
 * to stand in for.
 */
@Composable
internal fun StateRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    tint: Color = CursorTheme.colors.iconTertiary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp).padding(top = 1.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.base, color = colors.textSecondary)
                if (detail != null) Text(detail, style = type.small, color = colors.textQuaternary)
            }
        }
        if (actionLabel != null && onAction != null || secondaryLabel != null && onSecondary != null) {
            Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionLabel != null && onAction != null) CursorButton(actionLabel, onAction, height = 28.dp)
                if (secondaryLabel != null && onSecondary != null) CursorButton(secondaryLabel, onSecondary, height = 28.dp)
            }
        }
    }
}

@Composable
internal fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SpinnerRing(size = 13.dp)
        Spacer(Modifier.width(9.dp))
        Text(text, style = CursorTheme.typography.base, color = CursorTheme.colors.textQuaternary)
    }
}

/** The Extended-mode placeholder: what the section would show, and that the mode (or a later build) is what it waits on. */
@Composable
internal fun RequiresExtendedRow(availability: SectionAvailability.RequiresExtended, extendedOn: Boolean, modifier: Modifier = Modifier) {
    StateRow(
        icon = CursorIcons.Shield,
        title = if (!extendedOn) "Needs Extended mode" else if (availability.ready) "Extended mode is on" else "Arrives with a later build",
        detail = availability.reason + if (!extendedOn) ". Turn Extended mode on in Settings to use Cursor's undocumented endpoints for it." else "",
        tint = CursorTheme.colors.orange,
        modifier = modifier.testTag("requires-extended"),
    )
}

@Composable
internal fun EmptyRow(text: String, detail: String? = null, modifier: Modifier = Modifier) {
    StateRow(icon = CursorIcons.Eye, title = text, detail = detail, modifier = modifier.testTag("section-empty"))
}

@Composable
internal fun FailedRow(message: String, onRetry: (() -> Unit)?, secondaryLabel: String? = null, onSecondary: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    StateRow(
        icon = CursorIcons.Warning,
        title = "Couldn't load this",
        detail = message,
        tint = CursorTheme.colors.red,
        actionLabel = if (onRetry != null) "Retry" else null,
        onAction = onRetry,
        secondaryLabel = secondaryLabel,
        onSecondary = onSecondary,
        modifier = modifier.testTag("section-failed"),
    )
}

@Composable
internal fun UnsupportedRow(reason: String, url: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    StateRow(
        icon = CursorIcons.ExternalLink,
        title = "Open in the browser",
        detail = reason,
        actionLabel = if (url != null) "Open in browser" else null,
        onAction = url?.let { { onOpen(it) } },
        modifier = modifier.testTag("section-unsupported"),
    )
}
