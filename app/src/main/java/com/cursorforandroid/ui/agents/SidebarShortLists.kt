package com.cursorforandroid.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A long Projects or Pinned group, cut to its first [ROWS] rows (Settings › Appearance › "Shorten long Projects
 * list", on by default): the rest wait behind a "Show N more" row that lists them in place, and "Show less" cuts the
 * group back. The date groups keep every row. Only the top-level rows count; a Project's open tree goes with it.
 */
internal object SidebarShortList {
    const val ROWS = 5

    /** The groups that are cut short. */
    val KEYS: Set<String> = setOf(AgentListOrganizer.PROJECTS_KEY, AgentListOrganizer.PINNED_KEY)

    /** The rows a cut group shows, in the group's order, and how many it holds back. */
    data class Cut(val rows: List<AgentRow>, val hidden: Int)

    /**
     * The first [ROWS] of [rows], plus — when the open chat is further down — the row that holds it, as the next row,
     * so the selection is never what the cut hides. A worker open under a Project past the cut keeps that Project.
     */
    fun cut(rows: List<AgentRow>, selectedId: String?): Cut {
        if (rows.size <= ROWS) return Cut(rows, 0)
        val head = rows.take(ROWS)
        val kept = selectedId?.let { id -> rows.drop(ROWS).firstOrNull { it.holds(id) } }
        val shown = if (kept == null) head else head + kept
        return Cut(shown, rows.size - shown.size)
    }

    private fun AgentRow.holds(id: String): Boolean = agent.id == id || children.any { it.holds(id) }

    const val SHOW_LESS = "Show less"

    fun showMore(hidden: Int): String = "Show $hidden more"
}

/**
 * Which cut groups are listing every row, for this visit to the sidebar only. The shell [reset]s it whenever the
 * reader leaves — the drawer shut, a chat or a Project opened, another destination — and it is never saved, so a
 * sidebar always opens on the short lists.
 */
@Stable
class SidebarShortLists {
    private var expandedKeys by mutableStateOf(emptySet<String>())

    fun isExpanded(sectionKey: String): Boolean = sectionKey in expandedKeys

    fun expand(sectionKey: String) {
        expandedKeys = expandedKeys + sectionKey
    }

    fun collapse(sectionKey: String) {
        expandedKeys = expandedKeys - sectionKey
    }

    fun reset() {
        if (expandedKeys.isNotEmpty()) expandedKeys = emptySet()
    }
}

/**
 * The quiet row under a cut group: "Show 7 more" while rows are held back, "Show less" once they are listed. Sized
 * and inset like a chat row, its words where the rows' titles start, in the tone of the group labels; while a row
 * it holds back is unread it carries the folded header's dot, so nothing unread is lost behind the cut.
 */
@Composable
internal fun SidebarShowMoreRow(
    sectionKey: String,
    expanded: Boolean,
    hidden: Int,
    hasUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    Box(modifier.fillMaxWidth().padding(horizontal = CursorDimens.selectionInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClick, CursorTheme.shapes.base)
                .testTag("section-more-$sectionKey")
                .height(CursorDimens.sidebarRow)
                .padding(start = ShowMoreTextStart, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) SidebarShortList.SHOW_LESS else SidebarShortList.showMore(hidden),
                style = CursorTheme.typography.small,
                color = colors.textTertiary,
                maxLines = 1,
            )
            if (hasUnread && !expanded) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(UnreadDotSize)
                        .background(colors.unreadDot, CircleShape)
                        .semantics { contentDescription = "Unread chats among them" }
                        .testTag("section-more-unread-$sectionKey"),
                )
            }
        }
    }
}

/** Where a chat row's title starts inside its selection inset: its own padding, the glyph slot and the gap after it. */
private val ShowMoreTextStart = 8.dp + CursorDimens.glyph + 10.dp
