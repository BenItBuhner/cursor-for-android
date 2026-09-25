package com.cursorforandroid.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.cursorforandroid.ui.components.rememberSheetFocus
import com.cursorforandroid.ui.components.sheetFocus
import com.cursorforandroid.ui.panel.panelSlide
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sidebar as a column beside the detail pane on wide windows (tablets, the inner screen of a foldable, a phone on
 * its side), with the same slide the phone drawer has when it collapses and comes back.
 *
 * The column is measured at [width] — [CursorDimens.sidebarWidth] until the reader drags it wider or narrower — and
 * only its visible width animates: the content stays anchored to the moving edge, so it travels off to the start of
 * the window and returns from there rather than being wiped, and nothing inside it — the chat list, the search field —
 * lays out again for any frame of the motion. The detail pane beside it reflows into the room the rail gives up, as on
 * cursor.com. The hairline that separates the two is drawn over the column's end edge, so the rail takes [width] and
 * not a dp more, and rides inside the animated box so it always marks the boundary while it moves. [width] is read at
 * layout, so a drag resizes the column without recomposing the list in it.
 *
 * Unlike the phone drawer it covers nothing: the pane reflows beside it, so a composer holding the keyboard keeps both
 * while the rail comes back, where the drawer takes them. Collapsing, the rail is the drawer shutting: its own search,
 * holding focus, lets it go and the keyboard is put away as the collapse begins, and a composer beside it that holds
 * them keeps both ([com.cursorforandroid.ui.components.SheetFocus]).
 *
 * First composition with [expanded] true shows the rail at once, without a slide: that is what a Fold unfolding or a
 * phone rotating sees when the drawer layout is swapped for this one, and a rail that arrived by sliding in would
 * read as if it had opened on its own. So does a change with [animate] false, a key's (Ctrl+B, or a panel pinned by
 * Ctrl+Shift+B that takes the rail's room): the rail is where the key put it in the frame the key lands, with a slide
 * in flight dropped where it was. Hidden, the content leaves the composition, as it did when the rail was a plain `if`.
 *
 * With [yieldsToPanel], the rail is going for want of room beside a pinned panel, or coming back as the panel gives
 * the room up, and it slides as the panel does ([panelSlide]): the chat between the two narrows or widens without
 * turning back, where the drawer's slide, slower off the mark, would have it squeezed and then let out again.
 */
@Composable
fun SidebarRail(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    width: () -> Dp = { CursorDimens.sidebarWidth },
    animate: Boolean = true,
    yieldsToPanel: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = CursorTheme.colors
    val focus = rememberSheetFocus { expanded }
    val target = if (expanded) 1f else 0f
    // How much of the column shows: read while laying out, so neither the slide nor its end recomposes the content.
    val shown = remember { Animatable(target) }
    val onScreen by remember { derivedStateOf { shown.value > 0f } }
    LaunchedEffect(expanded, animate) {
        val distance = abs(target - shown.value)
        when {
            !animate -> shown.snapTo(target)
            yieldsToPanel -> shown.animateTo(target, panelSlide(distance))
            else -> {
                val millis = (SidebarRailMillis * distance).roundToInt().coerceAtLeast(MinRailMillis)
                shown.animateTo(target, tween(millis, easing = FastOutSlowInEasing))
            }
        }
    }
    // Around the slide rather than on it: hidden, the slide composes nothing, so nothing on it would stay to hear a
    // focused search leave with it.
    Box(modifier.sheetFocus(focus)) {
        if (expanded || (animate && onScreen)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val full = width().roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
                        val fraction = if (animate) shown.value else target
                        val shownWidth = (full * fraction).roundToInt()
                        layout(shownWidth, placeable.height) { placeable.placeRelative(shownWidth - full, 0) }
                    },
            ) {
                content()
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(CursorDimens.hairline).background(colors.strokeSubtle))
            }
        }
    }
}

/** The phone drawer's motion (Material's `ModalNavigationDrawer` settles over a 256ms tween), so both feel like one control. */
internal const val SidebarRailMillis = 256

/** The shortest a slide turned back near its end takes, so it still reads as motion. */
private const val MinRailMillis = 90
