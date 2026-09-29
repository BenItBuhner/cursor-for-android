package com.cursorforandroid.ui.customize

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * One quick action a sheet offers in its header: what it is called, what it does, and what it would act on right
 * now, or why it cannot ("17 unread chats", "Nothing unread"). A disabled action stays in place, dimmed, so the
 * header reads the same whatever the state.
 */
data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A compact header action beside the sheet's title: link-coloured medium text, an optional 15dp glyph before it,
 * over a [CursorDimens.touchTarget]-tall pressable box. [SheetAction.subtitle] is not drawn; it is what a screen
 * reader hears as the action's state.
 */
@Composable
fun SheetHeaderAction(action: SheetAction, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val tint = if (action.enabled) colors.link else colors.textQuaternary
    Row(
        modifier
            .pressable(action.onClick, CursorTheme.shapes.base, enabled = action.enabled)
            .height(CursorDimens.touchTarget)
            .padding(horizontal = 10.dp)
            .semantics { action.subtitle?.let { stateDescription = it } }
            .testTag("sheet-header-action-${action.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        action.icon?.let {
            Icon(it, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(action.label, style = CursorTheme.typography.baseMedium, color = tint, maxLines = 1)
    }
}

/**
 * "Read all": marks every loaded chat read. Its state is how many chats are unread right now; with none it says
 * so and the action is off.
 */
fun readAllAction(unreadCount: Int, onClick: () -> Unit): SheetAction = SheetAction(
    label = READ_ALL,
    icon = CursorIcons.CheckCheck,
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
