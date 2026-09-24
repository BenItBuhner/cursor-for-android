package com.cursorforandroid.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Where Android's back gesture begins — a strip down each side of the window — and whether the gesture under way began
 * in one. With gesture navigation a sideways swipe from either strip is the system's back, but the system takes the
 * pointer only once it has seen the finger move, and until then the app is handed the same down and first moves: a
 * quick swipe's first batch of them alone is past the app's touch slop, so a sheet that answered them would already be
 * sliding in under the back gesture when the system's cancel arrived. Neither the sidebar drawer nor the side panel
 * opens for a gesture that started in a strip; back is left to close whichever of them is open.
 *
 * Each strip is as wide as the window's system gesture insets make it on that side, which is where the system itself
 * starts the gesture (the user's back sensitivity is in them), and never narrower than [BackEdgeMinWidth]: 3-button
 * navigation reports no side insets at all, nor does a window before its first insets arrive.
 */
@Stable
class BackGestureEdges internal constructor(private val insets: WindowInsets, private val density: Density) {
    internal var coordinates: LayoutCoordinates? = null

    /** Whether the gesture under way began in a strip: decided on its first finger down, and kept until the next gesture's. */
    var gestureStartedInEdge: Boolean = false
        private set

    /** The width of the strip down the window's left edge, in pixels. */
    val leftPx: Int get() = maxOf(insets.getLeft(density, LayoutDirection.Ltr), minPx)

    /** The width of the strip down the window's right edge, in pixels. */
    val rightPx: Int get() = maxOf(insets.getRight(density, LayoutDirection.Ltr), minPx)

    private val minPx: Int get() = with(density) { BackEdgeMinWidth.roundToPx() }

    internal fun startGesture(position: Offset) {
        val coordinates = coordinates?.takeIf { it.isAttached }
        gestureStartedInEdge = coordinates != null && inEdge(coordinates.localToRoot(position).x, coordinates.findRootCoordinates().size.width)
    }

    private fun inEdge(x: Float, windowWidth: Int): Boolean = x < leftPx || x >= windowWidth - rightPx
}

@Composable
fun rememberBackGestureEdges(): BackGestureEdges {
    val insets = WindowInsets.systemGestures
    val density = LocalDensity.current
    return remember(insets, density) { BackGestureEdges(insets, density) }
}

/**
 * Keeps [edges] told where each gesture begins. Its first finger is seen on the initial pass, before anything inside has
 * seen it, and nothing is consumed: a finger in a strip that goes up or down, which the system leaves to the app, still
 * scrolls whatever is under it.
 */
fun Modifier.backGestureEdges(edges: BackGestureEdges): Modifier =
    onPlaced { edges.coordinates = it }
        .pointerInput(edges) {
            awaitEachGesture { edges.startGesture(awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position) }
        }

/** The narrowest a strip is, whatever the window reports. */
internal val BackEdgeMinWidth = 16.dp
