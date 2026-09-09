package com.cursorforandroid.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The sidebar as a column beside the detail pane on wide windows (tablets, the inner screen of a foldable, a phone on
 * its side), with the same slide the phone drawer has when it collapses and comes back.
 *
 * The column is measured at [CursorDimens.sidebarWidth] throughout and only its visible width animates: the content
 * stays anchored to the moving edge, so it travels off to the start of the window and returns from there rather than
 * being wiped, and nothing inside it — the chat list, the search field — lays out again for any frame of the motion.
 * The detail pane beside it reflows into the room the rail gives up, as on cursor.com. The hairline that separates the
 * two rides inside the animated box so it always marks the boundary while it moves.
 *
 * First composition with [expanded] true shows the rail at once, without a slide: that is what a Fold unfolding or a
 * phone rotating sees when the drawer layout is swapped for this one, and a rail that arrived by sliding in would
 * read as if it had opened on its own. Hidden, the content leaves the composition, as it did when the rail was a
 * plain `if`.
 */
@Composable
fun RowScope.SidebarRail(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    AnimatedVisibility(
        visible = expanded,
        enter = expandHorizontally(sidebarRailMotion(), expandFrom = Alignment.End),
        exit = shrinkHorizontally(sidebarRailMotion(), shrinkTowards = Alignment.End),
        label = "sidebarRail",
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxHeight()) {
            Box(Modifier.width(CursorDimens.sidebarWidth).fillMaxHeight()) { content() }
            Box(Modifier.fillMaxHeight().width(CursorDimens.hairline).background(colors.strokeSubtle))
        }
    }
}

/** The phone drawer's motion (Material's `ModalNavigationDrawer` settles over a 256ms tween), so both feel like one control. */
private fun sidebarRailMotion(): FiniteAnimationSpec<IntSize> = tween(SidebarRailMillis, easing = FastOutSlowInEasing)

/** Exposed for tests that step the clock through the slide. */
internal const val SidebarRailMillis = 256
