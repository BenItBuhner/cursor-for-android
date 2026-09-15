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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.ui.agents.MenuItem
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The tabs of a conversation's right-side panel, the surface cursor.com keeps beside a Project chat: the chat's
 * own sections ([Chat]), the Project's Context notes ([Project]), the Context stores as a tree with Recents
 * ([AllFiles]), one tab per Context document opened from them ([Document]) and one per side chat opened beside the
 * conversation ([SideChat]). [key] is the tab's identity for the strip and the view model; a document or side chat
 * tab can be closed, the three fixed tabs cannot.
 */
sealed interface PanelTab {
    val key: String
    val closable: Boolean get() = false

    /** The chat's sections: Overview, Changes, Pull request, Files, Artifacts, Side chats, Project, Usage. */
    data object Chat : PanelTab {
        override val key: String get() = "chat"
    }

    /** The Project's Context notes (`notes.md`), rendered as markdown under the Project's icon and name. */
    data object Project : PanelTab {
        override val key: String get() = "project"
    }

    /** The Context stores — the Project's and the user's — as trees, with the newest files under Recents. */
    data object AllFiles : PanelTab {
        override val key: String get() = "files"
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
            key == Chat.key -> Chat
            key == Project.key -> Project
            key == AllFiles.key -> AllFiles
            key.startsWith("doc:") -> key.removePrefix("doc:").split(':', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { Document(it[0], it[1]) }
            key.startsWith("side:") -> key.removePrefix("side:").takeIf { it.isNotBlank() }?.let(::SideChat)
            else -> null
        }
    }
}

/** Which tabs are open and which one is showing; see [PanelViewModel]. */
data class PanelTabsState(
    val open: List<PanelTab> = listOf(PanelTab.Chat),
    val selected: PanelTab = PanelTab.Chat,
) {
    /** [selected] when it is still open, else the first tab: a closed tab never leaves the panel on nothing. */
    val current: PanelTab get() = if (selected in open) selected else open.first()
}

/**
 * The strip along the top of the panel: one tab per [PanelTabsState.open] in order, the selected one on the panel's
 * surface and the rest dimmed, each closable one with its cross; a `+` at the end that opens a Context file (All
 * Files) or, where the account allows, starts a side chat; and the panel's own controls at the end edge. Scrolls
 * sideways once the tabs outgrow the panel, as the web's does.
 */
@Composable
internal fun PanelTabStrip(
    tabs: PanelTabsState,
    labels: (PanelTab) -> String,
    icons: (PanelTab) -> ImageVector,
    onSelect: (PanelTab) -> Unit,
    onClose: (PanelTab) -> Unit,
    onOpenFile: () -> Unit,
    onNewSideChat: (() -> Unit)?,
    onClosePanel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = CursorTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(start = 6.dp, end = 4.dp).testTag("panel-tabs"),
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
            Box {
                FlatIconButton(CursorIcons.Plus, "Open a tab", onClick = { menuOpen = true }, size = 28.dp, iconSize = 14.dp, modifier = Modifier.testTag("panel-tab-add"))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                    MenuItem("Open a file…", CursorIcons.Folder) { menuOpen = false; onOpenFile() }
                    if (onNewSideChat != null) MenuItem("New side chat", CursorIcons.Ask) { menuOpen = false; onNewSideChat() }
                }
            }
        }
        trailing()
        if (onClosePanel != null) FlatIconButton(CursorIcons.Close, "Close panel", onClick = onClosePanel)
    }
}

/**
 * One tab of the strip: the tab's glyph and name, the surface behind the selected one; a closable tab carries its
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
            .height(30.dp)
            .background(if (selected) colors.fillSoft else Color.Transparent, shape)
            .pressable(onSelect, shape, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(start = 8.dp, end = if (closable) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) colors.iconPrimary else colors.iconTertiary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = type.small,
            color = if (selected) colors.textPrimary else colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = TabLabelMaxWidth),
        )
        if (closable) {
            Spacer(Modifier.width(2.dp))
            TouchTarget(size = 18.dp, touchSize = 32.dp, shape = CircleShape, onClick = onClose) {
                Icon(CursorIcons.Close, "Close $label", tint = colors.iconTertiary, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** The web's tab labels ellipsise around this width; a long document name yields to the tabs beside it. */
private val TabLabelMaxWidth = 148.dp

/** A folder in the All Files tree, opened or closed. */
internal data class TreeFolderKey(val store: AgentStoreRef, val entry: ContextEntry) {
    val id: String get() = "${store.storeId}:${entry.relativePath}"
}

/** The semantic description of a panel tab, for TalkBack and the tests. */
internal fun Modifier.panelTabDescription(label: String): Modifier = semantics { contentDescription = "Panel tab $label" }
