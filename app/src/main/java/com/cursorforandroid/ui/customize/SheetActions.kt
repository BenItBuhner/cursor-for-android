package com.cursorforandroid.ui.customize

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * One quick action a sheet offers at its top: what it is called, what it does, and — under the label — what it
 * would act on right now, or why it cannot ("17 unread chats", "Nothing unread"). A disabled action stays listed,
 * dimmed, so the sheet reads the same whatever the state.
 */
data class SheetAction(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The "Actions" group of a sheet: its header and one card holding [actions], a row each with a hairline between.
 * Built to take more rows as quick actions are added; today the Chats sheet has one (see [readAllAction]).
 */
@Composable
fun ActionsSection(actions: List<SheetAction>, modifier: Modifier = Modifier, title: String = "Actions") {
    val colors = CursorTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = CursorTheme.typography.small,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        )
        // A faint fill over the sheet's own surface, so the card reads as one group against the flat rows below it.
        CursorCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("sheet-actions"), fill = colors.fillFaint) {
            actions.forEachIndexed { index, action ->
                if (index > 0) HairlineDivider(Modifier.padding(horizontal = 12.dp))
                ActionRow(action)
            }
        }
    }
}

/** One row of an [ActionsSection]: the glyph at 66 %, the label, its subtitle beneath; dimmed and not pressable when disabled. */
@Composable
fun ActionRow(action: SheetAction) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(action.onClick, CursorTheme.shapes.lg, enabled = action.enabled)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("sheet-action-${action.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon, null, tint = if (action.enabled) colors.iconSecondary else colors.iconQuaternary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(action.label, style = type.base, color = if (action.enabled) colors.textPrimary else colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            action.subtitle?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * "Read all": marks every loaded chat read. Its subtitle is how many chats are unread right now; with none it says
 * so and the row is off.
 */
fun readAllAction(unreadCount: Int, onClick: () -> Unit): SheetAction = SheetAction(
    icon = CursorIcons.CheckCheck,
    label = READ_ALL,
    subtitle = when (unreadCount) {
        0 -> "Nothing unread"
        1 -> "1 unread chat"
        else -> "$unreadCount unread chats"
    },
    enabled = unreadCount > 0,
    onClick = onClick,
)

/** The read-all action's label, as the sheet and its tests spell it. */
const val READ_ALL = "Read all"
