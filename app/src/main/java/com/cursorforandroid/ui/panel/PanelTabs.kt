package com.cursorforandroid.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.horizontalScrollEdgeFade
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The panel's tabs, as cursor.com's right panel keeps them: two that are always there — the Project ([Project], a
 * chat inside a Project only) and the chat's own sections ([Details]) — and one per thing opened from them, each
 * closable: an agent's transcript ([Agent]), a Context document ([Document]), a file the agent touched or the
 * repository holds ([File]), a picture or a recording ([Media]). [key] is a tab's identity for the strip and the view
 * model; opening what is already open selects its tab.
 */
sealed interface PanelTab {
    val key: String
    val closable: Boolean get() = true

    /** The Project: its notes (`notes.md` in its Context) under its icon and name, or its files (All Files). */
    data object Project : PanelTab {
        override val key: String get() = "project"
        override val closable: Boolean get() = false
    }

    /** The chat's own sections: Overview, Changes, Pull request, Files, Artifacts, Side chats, Project, Usage. */
    data object Details : PanelTab {
        override val key: String get() = "details"
        override val closable: Boolean get() = false
    }

    /** Another chat — a worker, a subagent, a side chat, the Project's coordinator — read beside this one. */
    data class Agent(val agentId: String) : PanelTab {
        override val key: String get() = "agent:$agentId"
    }

    /** A file of a Context store: Preview or Source. */
    data class Document(val storeId: String, val path: String) : PanelTab {
        override val key: String get() = "doc:$storeId:$path"
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    /** A file named by the transcript, the repository, the workspace or the branch diff; what it shows is [PanelState.files]. */
    data class File(val path: String) : PanelTab {
        override val key: String get() = "file:$path"
        val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
    }

    /** A picture or a recording, from the chat's artifacts, the files it touched or its Context. */
    data class Media(val src: String, val name: String, val isVideo: Boolean = false) : PanelTab {
        override val key: String get() = "media:$src"
    }
}

/** The tabs opened this session, in the order they were opened, and the key of the one showing (null: the panel's home tab). */
data class PanelTabsState(
    val open: List<PanelTab> = emptyList(),
    val selectedKey: String? = null,
)

/**
 * Whether the host lets the panel widen over the chat, and whether it is widened: the strip's expand control, which
 * is not drawn where the panel already takes what a screen can give it (a phone's sheet).
 */
@Stable
class PanelExpand(val available: Boolean, val expanded: Boolean, val toggle: () -> Unit)

val LocalPanelExpand = compositionLocalOf<PanelExpand?> { null }

/** An entry of the strip's `+` menu. */
internal class StripAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The strip along the top of the panel, as cursor.com draws it: the tabs, each its glyph and name, the selected one
 * on a soft fill with its cross; `+` after them; at the end edge the panel's own controls, expand and close, then a
 * hairline. Scrolls sideways once the tabs outgrow the panel, and brings the selected tab into view as it changes.
 */
@Composable
internal fun PanelTabStrip(
    tabs: List<PanelTab>,
    selected: PanelTab,
    label: (PanelTab) -> String,
    leading: @Composable (tab: PanelTab, selected: Boolean) -> Unit,
    onSelect: (PanelTab) -> Unit,
    onClose: (PanelTab) -> Unit,
    menu: List<StripAction>,
    onClosePanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val expand = LocalPanelExpand.current
    var menuOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(StripHeight).padding(start = 8.dp, end = 4.dp).testTag("panel-tabs"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScrollEdgeFade(scroll.canScrollBackward, scroll.canScrollForward).horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEach { tab ->
                    key(tab.key) {
                        val isSelected = tab.key == selected.key
                        TabChip(
                            label = label(tab),
                            selected = isSelected,
                            closable = tab.closable,
                            italic = tab is PanelTab.Agent,
                            leading = { leading(tab, isSelected) },
                            onSelect = { onSelect(tab) },
                            onClose = { onClose(tab) },
                            modifier = Modifier.testTag("panel-tab-${tab.key}"),
                        )
                    }
                }
                if (menu.isNotEmpty()) {
                    Box {
                        FlatIconButton(CursorIcons.Plus, "Open a tab", onClick = { menuOpen = true }, size = 28.dp, iconSize = 14.dp, tint = colors.iconSecondary, touchHeight = StripHeight, modifier = Modifier.testTag("panel-tab-add"))
                        CursorMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menu.forEach { action -> CursorMenuItem(action.label, action.icon) { menuOpen = false; action.onClick() } }
                        }
                    }
                }
            }
            if (expand != null && expand.available) {
                FlatIconButton(
                    if (expand.expanded) PanelIcons.Collapse else PanelIcons.Expand,
                    if (expand.expanded) "Restore panel width" else "Expand panel",
                    onClick = expand.toggle,
                    size = 28.dp,
                    iconSize = 13.dp,
                    tint = colors.iconSecondary,
                    touchHeight = StripHeight,
                    modifier = Modifier.testTag("panel-expand"),
                )
            }
            // The strip's controls take taps the strip's height and no taller, so none reaches under the status bar.
            FlatIconButton(PanelIcons.PanelRight, "Close panel", onClick = onClosePanel, size = 28.dp, iconSize = 15.dp, tint = colors.iconSecondary, touchHeight = StripHeight, modifier = Modifier.testTag("panel-close"))
        }
        HairlineDivider()
    }
}

/**
 * One tab: its glyph and name on the web's 26px chip, the selected one filled and carrying its cross; another chat's
 * name is set in italics, as the web sets a worker's. The whole height of the strip takes the tap, so a tab is as easy
 * to hit as the controls beside it; the cross is a target of its own, labelled, since inside the tab's node it would
 * otherwise merge into it and a tap on it would select the tab instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    closable: Boolean,
    italic: Boolean,
    leading: @Composable () -> Unit,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    val interaction = remember { MutableInteractionSource() }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (!selected) return@LaunchedEffect
        // Asked before the strip's first layout, the scroll has nowhere to bring the tab to yet.
        withFrameNanos { }
        requester.bringIntoView()
    }
    Box(
        modifier
            .bringIntoViewRequester(requester)
            .height(StripHeight)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(ChipHeight)
                .clip(shape)
                .background(if (selected) colors.fillSoft else Color.Transparent)
                .indication(interaction, ripple(color = colors.base, bounded = true))
                .padding(start = 8.dp, end = if (closable && selected) 3.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = if (italic) type.base.copy(fontStyle = FontStyle.Italic) else type.base,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = TabLabelMaxWidth),
            )
            if (closable && selected) {
                Spacer(Modifier.width(2.dp))
                TouchTarget(size = 20.dp, touchSize = 36.dp, shape = CircleShape, onClick = onClose, contentDescription = "Close $label") {
                    Icon(CursorIcons.Close, null, tint = colors.iconTertiary, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

/** A tab's glyph at the strip's size, in its selected or resting tint. */
@Composable
internal fun TabIcon(icon: ImageVector, selected: Boolean, tint: Color = Color.Unspecified) {
    val colors = CursorTheme.colors
    Icon(icon, null, tint = if (tint != Color.Unspecified) tint else if (selected) colors.iconPrimary else colors.iconSecondary, modifier = Modifier.size(13.dp))
}

/** The strip's height and a chip's, as measured off the web: a 40px row, 26px chips. */
internal val StripHeight = 40.dp
private val ChipHeight = 26.dp

/** The web's tab labels ellipsise around this width; a long name yields to the tabs beside it. */
private val TabLabelMaxWidth = 168.dp
