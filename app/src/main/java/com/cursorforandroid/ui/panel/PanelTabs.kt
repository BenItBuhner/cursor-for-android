package com.cursorforandroid.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The tabs of the Project panel cursor.com keeps beside a Project chat: the Project itself ([Project] — its notes,
 * or its files in the All Files mode), one tab per Context document opened from them ([Document]) and one per side
 * chat opened beside the conversation ([SideChat]). [key] is the tab's identity for the strip and the view model;
 * a document or side chat tab can be closed, the Project tab cannot.
 */
sealed interface PanelTab {
    val key: String
    val closable: Boolean get() = false

    /** The Project: its Context notes (`notes.md`) rendered as markdown under its icon and name, or All Files. */
    data object Project : PanelTab {
        override val key: String get() = "project"
    }

    /** One Context file opened from All Files or the notes: Preview or Source. */
    data class Document(val storeId: String, val path: String) : PanelTab {
        override val key: String get() = "doc:$storeId:$path"
        override val closable: Boolean get() = true
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    /** A side chat of this conversation, open beside it rather than in its place. */
    data class SideChat(val agentId: String) : PanelTab {
        override val key: String get() = "side:$agentId"
        override val closable: Boolean get() = true
    }

    companion object {
        /** Parses a [key] back into its tab; null for a key this build does not write. */
        fun fromKey(key: String): PanelTab? = when {
            key == Project.key -> Project
            key.startsWith("doc:") -> key.removePrefix("doc:").split(':', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { Document(it[0], it[1]) }
            key.startsWith("side:") -> key.removePrefix("side:").takeIf { it.isNotBlank() }?.let(::SideChat)
            else -> null
        }
    }
}

/**
 * Which surface the panel shows: the Project panel — the tab strip with the Project's notes, its files, its
 * documents and side chats — or the chat's own sections (Overview, Changes, Pull request, Files, Artifacts, Side
 * chats, Usage), the panel every chat had before the Project surface and still has.
 */
enum class PanelSurface { Project, Chat }

/** Which tabs are open and which one is showing; see [PanelViewModel]. */
data class PanelTabsState(
    val open: List<PanelTab> = listOf(PanelTab.Project),
    val selected: PanelTab? = PanelTab.Project,
) {
    /** [selected] when it is still open, else the first tab; null when nothing is open. */
    val current: PanelTab? get() = if (selected != null && selected in open) selected else open.firstOrNull()
}

/**
 * The strip along the top of the Project panel, as cursor.com draws it: the Project tab, then one tab per open
 * document — its file glyph, its name and a cross — and side chat, then `+`; at the end edge the panel's own
 * controls, expand and close. Measured off the reference: a 44px row, the selected tab on a 6px-radius fill with 14px
 * of padding, 12px glyphs, 13px labels. Scrolls sideways once the tabs outgrow the panel.
 */
@Composable
internal fun PanelTabStrip(
    tabs: PanelTabsState,
    labels: (PanelTab) -> String,
    icons: (PanelTab) -> ImageVector,
    onSelect: (PanelTab) -> Unit,
    onClose: (PanelTab) -> Unit,
    onOpenFile: (() -> Unit)?,
    onNewSideChat: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    expanded: Boolean,
    onClosePanel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = StripInset, end = 8.dp).testTag("panel-tabs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.open.forEach { tab ->
                PanelTabChip(
                    label = labels(tab),
                    icon = icons(tab),
                    selected = tab == tabs.current,
                    closable = tab.closable,
                    onSelect = { onSelect(tab) },
                    onClose = { onClose(tab) },
                    modifier = Modifier.testTag("panel-tab-${tab.key}"),
                )
            }
            if (onOpenFile != null || onNewSideChat != null) {
                Box {
                    FlatIconButton(CursorIcons.Plus, "Open a tab", onClick = { menuOpen = true }, size = 28.dp, iconSize = 14.dp, tint = colors.iconSecondary, modifier = Modifier.testTag("panel-tab-add"))
                    CursorMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onOpenFile != null) CursorMenuItem("Open a file…", CursorIcons.Folder) { menuOpen = false; onOpenFile() }
                        if (onNewSideChat != null) CursorMenuItem("New side chat", CursorIcons.Ask) { menuOpen = false; onNewSideChat() }
                    }
                }
            }
        }
        if (onExpand != null) FlatIconButton(CursorIcons.Expand, if (expanded) "Restore panel" else "Expand panel", onClick = onExpand, size = 28.dp, iconSize = 13.dp, modifier = Modifier.testTag("panel-expand"))
        if (onClosePanel != null) FlatIconButton(CursorIcons.PanelRight, "Close panel", onClick = onClosePanel, size = 28.dp, iconSize = 14.dp)
    }
}

/**
 * One tab of the strip: the tab's glyph and name, the fill behind the selected one; a closable tab carries its
 * cross, which takes it off without selecting it.
 */
@Composable
private fun PanelTabChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    closable: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        modifier
            .height(28.dp)
            .background(if (selected) colors.fillSoft else Color.Transparent, shape)
            .pressable(onSelect, shape, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(start = 8.dp, end = if (closable) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) colors.iconPrimary else colors.iconTertiary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = type.base,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = TabLabelMaxWidth),
        )
        if (closable) {
            Spacer(Modifier.width(2.dp))
            // The label sits on the control that takes the tap: inside the chip's own tappable row the glyph's node
            // would merge into the chip's, and a tap aimed at the cross would select the tab instead.
            TouchTarget(size = 18.dp, touchSize = 32.dp, shape = CircleShape, onClick = onClose, contentDescription = "Close $label") {
                Icon(CursorIcons.Close, null, tint = colors.iconTertiary, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** Where the strip's first tab starts, as the web's does: 16px in from the panel's edge. */
private val StripInset = 16.dp

/** The web's tab labels ellipsise around this width; a long document name yields to the tabs beside it. */
private val TabLabelMaxWidth = 160.dp
