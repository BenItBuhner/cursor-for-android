package com.cursorforandroid.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The sidebar as a column beside the detail pane on wide windows (tablets, the inner screen of a foldable, a phone on
 * its side), in one of three states: the full column ([RailState.Expanded]), its glyphs alone ([RailState.IconOnly],
 * [WindowPosture.RAIL_ICON_DP] wide) or nothing ([RailState.Hidden]) — with the same slide the phone drawer has
 * between any two of them.
 *
 * Only the visible width animates: the content is measured at its state's full width and anchored to the moving
 * edge, so it travels off to the start of the window and returns from there rather than being wiped, and nothing
 * inside it — the chat list, the search field — lays out again for any frame of the motion. The detail pane beside
 * it reflows into the room the rail gives up, as on cursor.com. The hairline that separates the two rides beside
 * the animated box so it always marks the boundary while it moves.
 *
 * First composition shows the rail at rest in its state, without a slide: that is what a Fold unfolding or a phone
 * rotating sees when the drawer layout is swapped for this one, and a rail that arrived by sliding in would read as
 * if it had opened on its own. Hidden, the content leaves the composition once the slide has finished.
 */
@Composable
fun RowScope.SidebarRail(
    state: RailState,
    modifier: Modifier = Modifier,
    iconContent: @Composable () -> Unit = {},
    expandedContent: @Composable () -> Unit,
) {
    val colors = CursorTheme.colors
    val width by animateDpAsState(targetValue = state.railWidth, animationSpec = tween(SidebarRailMillis, easing = FastOutSlowInEasing), label = "sidebarRail")
    // What is drawn while the rail slides shut is what was showing before: the content keeps its shape on its way out.
    var shown by remember { mutableStateOf(state.takeUnless { it == RailState.Hidden } ?: RailState.Expanded) }
    if (state != RailState.Hidden) shown = state
    if (width > 0.dp || state != RailState.Hidden) {
        Row(modifier.fillMaxHeight()) {
            Box(Modifier.width(width).fillMaxHeight().clipToBounds()) {
                Box(Modifier.align(Alignment.CenterEnd).width(shown.railWidth).fillMaxHeight()) {
                    if (shown == RailState.IconOnly) iconContent() else expandedContent()
                }
            }
            if (width > 0.dp) Box(Modifier.fillMaxHeight().width(CursorDimens.hairline).background(colors.strokeSubtle))
        }
    }
}

/** The two-state rail earlier builds had: expanded or hidden, no glyph column. */
@Composable
fun RowScope.SidebarRail(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SidebarRail(state = if (expanded) RailState.Expanded else RailState.Hidden, modifier = modifier, expandedContent = content)
}

/** How wide the rail is in each state. */
val RailState.railWidth: Dp
    get() = when (this) {
        RailState.Expanded -> CursorDimens.sidebarWidth
        RailState.IconOnly -> WindowPosture.RAIL_ICON_DP.dp
        RailState.Hidden -> 0.dp
    }

/** The phone drawer's motion (Material's `ModalNavigationDrawer` settles over a 256ms tween), so both feel like one control. Exposed for tests that step the clock through the slide. */
internal const val SidebarRailMillis = 256
