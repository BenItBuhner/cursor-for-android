package com.cursorforandroid.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.StateGlyph
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

data class AgentRowActions(
    val onOpen: (AgentRow) -> Unit,
    val onTogglePin: (AgentRow) -> Unit,
    val onArchive: (AgentRow) -> Unit,
    val onUnarchive: (AgentRow) -> Unit,
    val onRename: (AgentRow, String) -> Unit,
    val onSnooze: (AgentRow, Long) -> Unit,
    val onUnsnooze: (AgentRow) -> Unit,
)

/**
 * Sidebar row in the web's proportions: selection is a 6 % fill inset from both edges with radius 6, the state glyph
 * sits in a fixed slot so titles align whether or not a row has one, trailing metadata is at 36 %.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentRowItem(
    row: AgentRow,
    selected: Boolean,
    prefs: ListPreferences,
    actions: AgentRowActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = AppClock.now(),
    /** The share picker only opens a chat; the pin / archive / delete menu stays on the sidebar. */
    showMenu: Boolean = true,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var snoozeOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val agent = row.agent

    Box(modifier.fillMaxWidth().padding(horizontal = CursorDimens.selectionInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.fillSoft else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(color = colors.base),
                    onClick = { actions.onOpen(row) },
                    onLongClick = if (showMenu) ({ menuOpen = true }) else null,
                )
                .height(CursorDimens.sidebarRow)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateGlyph(row.indicator, hasBranch = agent.hasBranch, hasPullRequest = agent.hasPullRequest, pullRequest = row.pullRequest)
            Spacer(Modifier.width(10.dp))
            Text(
                agent.name,
                style = type.row,
                color = if (agent.isArchived) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val trailing = buildList {
                if (prefs.showWorkspace) agent.repoShortName?.let { add(it) }
                if (prefs.showRuntime) add(TimeFormat.relativeShort(agent.updatedAtMillis, nowMillis))
            }
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(trailing.joinToString(" · "), style = type.base, color = colors.textQuaternary, maxLines = 1)
            }
            if (prefs.showBranchStatus && agent.envType == EnvType.MACHINE) {
                Spacer(Modifier.width(8.dp))
                Icon(CursorIcons.Desktop, "Self-hosted machine", tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            }
        }
        ChatOverflowMenu(
            row = row,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onRename = { menuOpen = false; renameOpen = true },
            onSnooze = { menuOpen = false; snoozeOpen = true },
            actions = actions,
        )
        if (renameOpen) {
            RenameChatDialog(
                initialName = agent.name,
                onConfirm = { name -> renameOpen = false; actions.onRename(row, name) },
                onDismiss = { renameOpen = false },
            )
        }
        if (snoozeOpen) {
            SnoozeChatDialog(
                onPick = { until -> snoozeOpen = false; actions.onSnooze(row, until) },
                onDismiss = { snoozeOpen = false },
            )
        }
    }
}

/** Pin / rename / link / snooze / archive — the long-press menu on a sidebar or recent-chat row. */
@Composable
fun ChatOverflowMenu(
    row: AgentRow,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onSnooze: () -> Unit,
    actions: AgentRowActions,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val agent = row.agent
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = CursorTheme.colors.elevated, shape = CursorTheme.shapes.lg) {
        MenuItem(if (row.isPinned) "Unpin" else "Pin", CursorIcons.Pin) { onDismiss(); actions.onTogglePin(row) }
        MenuItem("Rename", CursorIcons.Pencil) { onRename() }
        MenuItem("Open on cursor.com", CursorIcons.ExternalLink) { onDismiss(); uriHandler.openUri(agent.url) }
        MenuItem("Copy link", CursorIcons.Copy) { onDismiss(); clipboard.setText(AnnotatedString(agent.url)) }
        if (!agent.isArchived) {
            if (row.isSnoozed) {
                MenuItem("Unsnooze", CursorIcons.Clock) { onDismiss(); actions.onUnsnooze(row) }
            } else {
                MenuItem("Snooze", CursorIcons.Clock) { onSnooze() }
            }
        }
        if (agent.isArchived) {
            MenuItem("Unarchive", CursorIcons.Archive) { onDismiss(); actions.onUnarchive(row) }
        } else {
            MenuItem("Archive", CursorIcons.Archive) { onDismiss(); actions.onArchive(row) }
        }
    }
}

/** Rename field as the official apps expose it: one line, 100 characters, same cap as create. */
@Composable
fun RenameChatDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val focus = remember { FocusRequester() }
    var field by remember {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    val trimmed = field.text.trim()
    val canSave = trimmed.isNotEmpty() && trimmed.length <= NAME_MAX
    fun save() {
        if (canSave) onConfirm(trimmed)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        title = { Text("Rename chat", style = type.sectionTitle) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.fillFaint, CursorTheme.shapes.base)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { incoming ->
                        field = if (incoming.text.length <= NAME_MAX) incoming else incoming.copy(text = incoming.text.take(NAME_MAX))
                    },
                    singleLine = true,
                    textStyle = type.base.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (field.text.isEmpty()) Text("Chat name", style = type.base, color = colors.textQuaternary)
                            inner()
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = canSave) {
                Text("Rename", style = type.baseMedium, color = if (canSave) colors.textPrimary else colors.textQuaternary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = type.baseMedium, color = colors.textSecondary) } },
    )
}

private const val NAME_MAX = 100

/** Context-menu row: 13sp label with a 16px glyph at 66 %, tall enough to tap without care. */
@Composable
internal fun MenuItem(label: String, icon: ImageVector, tint: Color = CursorTheme.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.base, color = tint) },
        leadingIcon = { Icon(icon, null, tint = if (tint == CursorTheme.colors.textPrimary) CursorTheme.colors.iconSecondary else tint, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp),
        modifier = Modifier.height(40.dp),
    )
}
