package com.cursorforandroid.ui.agents

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The header of one sidebar group — Projects, Pinned, Today, Yesterday, Last 7 Days, Last 30 Days, Older — in the
 * group label's own voice (12sp at 60 %), with a chevron at the trailing edge that folds the group closed and open
 * again: down while the rows are out, turned to the right while they are folded, turning between the two. The whole
 * row is the toggle; the chevron is a button of its own too, so a thumb aimed at it lands.
 *
 * Folded, the header stands for its rows: the title carries their count ("Today · 12"), and a small dot follows it
 * while any of them is unread, so nothing is lost behind a closed group. The Projects header also carries the plus
 * that creates a Project ([onNewProject], from #174), immediately to the left of the chevron and drawn as its pair —
 * the same [HeaderGlyphButton], the same size, the same touch target.
 *
 * Presentation only: which rows the section holds, and in which order, is the organizer's (and the desktop's rules
 * from #132) and is not read here beyond the count and the unread mark.
 */
@Composable
fun SidebarSectionHeader(
    section: AgentSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onNewProject: (() -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val title = section.title
    val hasUnread = !expanded && section.rows.any { it.isUnread }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(CursorDimens.sidebarRow + CursorDimens.sidebarRowGap)
            // No highlight on the row itself, as the desktop's group headers have none; the chevron's disc answers the press.
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse" else "Expand",
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(start = 16.dp, end = HeaderEndPadding)
            .testTag("section-${section.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!expanded) {
            // The rows the fold hides, counted, in the quieter tone the rows' own metadata uses.
            Text(" · ${section.rows.size}", style = type.small, color = colors.textQuaternary, maxLines = 1, modifier = Modifier.testTag("section-count-${section.key}"))
        }
        if (hasUnread) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(UnreadDotSize)
                    .background(colors.unreadDot, CircleShape)
                    .semantics { contentDescription = "Unread chats in $title" }
                    .testTag("section-unread-${section.key}"),
            )
        }
        Spacer(Modifier.weight(1f))
        if (onNewProject != null) {
            HeaderGlyphButton(CursorIcons.Plus, "New Project", onClick = onNewProject, modifier = Modifier.testTag("new-project"))
            Spacer(Modifier.width(HeaderGlyphGap))
        }
        val rotation by animateFloatAsState(if (expanded) 0f else -90f, tween(ChevronTurnMillis), label = "section-chevron")
        HeaderGlyphButton(
            CursorIcons.ChevronDown,
            if (expanded) "Collapse $title" else "Expand $title",
            onClick = onToggle,
            modifier = Modifier.testTag("section-chevron-${section.key}"),
            glyphModifier = Modifier.rotate(rotation),
        )
    }
}

/**
 * A header's small round button — the Projects plus, every group's chevron: a 14dp glyph at the quaternary tone in
 * a 20dp disc that lights on press, taking touches over 32dp. One composable for both, so the pair matches.
 */
@Composable
internal fun HeaderGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyphModifier: Modifier = Modifier,
) {
    TouchTarget(
        size = HeaderGlyphButtonSize,
        touchSize = HeaderGlyphTouchSize,
        shape = CircleShape,
        onClick = onClick,
        contentDescription = contentDescription,
        // A node of its own inside the header's merged row, so the disc (not the row) is what a tag finds and measures.
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Icon(icon, null, tint = CursorTheme.colors.iconQuaternary, modifier = glyphModifier.size(HeaderGlyphSize))
    }
}

/** The disc of a header's glyph button: the plus's size before the chevron joined it. */
internal val HeaderGlyphButtonSize = 20.dp
/** The glyph inside it. */
internal val HeaderGlyphSize = 14.dp
/** The touch the disc accepts, over its edges. */
internal val HeaderGlyphTouchSize = 32.dp
/** Between the plus and the chevron: close enough to read as a pair, apart enough to aim at either. */
internal val HeaderGlyphGap = 4.dp
/** The header's end inset, so the chevron's disc sits in line with the rows' trailing metadata. */
internal val HeaderEndPadding = 10.dp
/** The unread dot on a folded header: smaller than a row's, a hint rather than a badge. */
internal val UnreadDotSize = 5.dp
/** How long the chevron takes to turn. */
internal const val ChevronTurnMillis = 180
