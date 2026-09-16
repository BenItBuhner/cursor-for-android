package com.cursorforandroid.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/**
 * The sidebar as a column beside the detail pane on wide windows (tablets, the inner screen of a foldable, a phone on
 * its side): the full sidebar ([RailState.Expanded]) at [width], or nothing ([RailState.Hidden]), the two states
 * cursor.com's toggle moves between — with the same slide the phone drawer has.
 *
 * Only the visible width animates: the content is measured at the sidebar's width and anchored to the moving edge,
 * so it travels off to the start of the window and returns from there rather than being wiped, and nothing inside
 * it — the chat list, the search field — lays out again for any frame of the slide. The detail pane beside it
 * reflows into the room the rail gives up, as on cursor.com. The hairline that separates the two rides beside the
 * animated box so it always marks the boundary while it moves.
 *
 * The trailing edge is a grip, as the web's is: dragging it hands each width the finger passes to [onResize] (in
 * dp; the shell clamps it to [WindowPosture.clampSidebarWidth] and lays the column out at the result, so the content
 * and any pane reflow live and never overlap), the width the finger lifts on to [onResizeEnd] (the one to keep), and
 * a drag under [WindowPosture.SIDEBAR_HIDE_BELOW_DP] to [onHide]: the sidebar snaps shut rather than stopping at
 * its minimum. Without [onResize] the edge is a plain hairline. The slide is skipped while a finger is on the grip
 * so the column follows it exactly.
 *
 * First composition shows the rail at rest in its state, without a slide: that is what a Fold unfolding or a phone
 * rotating sees when the drawer layout is swapped for this one, and a rail that arrived by sliding in would read as
 * if it had opened on its own. Hidden, the content leaves the composition once the slide has finished.
 */
@Composable
fun RowScope.SidebarRail(
    state: RailState,
    modifier: Modifier = Modifier,
    width: Dp = CursorDimens.sidebarWidth,
    onResize: ((Int) -> Unit)? = null,
    onResizeEnd: ((Int) -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var dragging by remember { mutableStateOf(false) }
    // Where the finger has asked the edge to be, unclamped: the shell's clamp decides what is drawn, this decides
    // when the drag has gone far enough under the minimum to snap the sidebar shut.
    var askedPx by remember { mutableFloatStateOf(0f) }
    val target = if (state == RailState.Hidden) 0.dp else width
    val shown by animateDpAsState(
        targetValue = target,
        animationSpec = if (dragging) snap() else tween(SidebarRailMillis, easing = FastOutSlowInEasing),
        label = "sidebarRail",
    )
    // A drag that snapped the sidebar shut ends with its grip: the flag is cleared here so the next slide animates.
    LaunchedEffect(state) { if (state == RailState.Hidden) dragging = false }
    val dragState = rememberDraggableState { delta ->
        askedPx += if (rtl) -delta else delta
        val askedDp = with(density) { askedPx.toDp() }.value
        if (askedDp < WindowPosture.SIDEBAR_HIDE_BELOW_DP && onHide != null) {
            onHide()
        } else {
            onResize?.invoke(askedDp.roundToInt())
        }
    }
    // Resizable, the column is [width] all told — the grip's strip takes the trailing edge of it, so the shell's
    // arithmetic (window less sidebar less pane) is the layout's. Without a grip the hairline rides beside the
    // column as it always has.
    val edge = if (onResize != null) GripWidth else CursorDimens.hairline
    val inset = if (onResize != null) GripWidth else 0.dp
    if (shown > 0.dp || state != RailState.Hidden) {
        Row(modifier.fillMaxHeight()) {
            Box(Modifier.width((shown - inset).coerceAtLeast(0.dp)).fillMaxHeight().clipToBounds()) {
                Box(Modifier.align(Alignment.CenterEnd).width((width - inset).coerceAtLeast(0.dp)).fillMaxHeight()) { content() }
            }
            if (shown > 0.dp) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(edge)
                        .then(
                            if (onResize != null) {
                                Modifier
                                    .draggable(
                                        state = dragState,
                                        orientation = Orientation.Horizontal,
                                        onDragStarted = {
                                            dragging = true
                                            askedPx = with(density) { width.toPx() }
                                        },
                                        onDragStopped = {
                                            dragging = false
                                            if (state != RailState.Hidden) onResizeEnd?.invoke(with(density) { askedPx.toDp() }.value.roundToInt())
                                        },
                                    )
                                    .semantics {
                                        contentDescription = "Resize sidebar"
                                        stateDescription = "${width.value.roundToInt()} dp"
                                    }
                                    .testTag("sidebar-grip")
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxHeight().width(CursorDimens.hairline).background(colors.strokeSubtle))
                    if (onResize != null) Box(Modifier.width(3.dp).height(28.dp).background(colors.strokeStrong, CircleShape))
                }
            }
        }
    }
}

/** The rail by a flag: expanded or hidden. */
@Composable
fun RowScope.SidebarRail(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SidebarRail(state = if (expanded) RailState.Expanded else RailState.Hidden, modifier = modifier, content = content)
}

/** How wide the rail is in each state at the web's resting width. */
val RailState.railWidth: Dp
    get() = when (this) {
        RailState.Expanded -> CursorDimens.sidebarWidth
        RailState.Hidden -> 0.dp
    }

/** The grip's strip on the trailing edge: the hairline in its middle, the drag over the whole of it. */
private val GripWidth = 10.dp

/** The phone drawer's motion (Material's `ModalNavigationDrawer` settles over a 256ms tween), so both feel like one control. Exposed for tests that step the clock through the slide. */
internal const val SidebarRailMillis = 256
