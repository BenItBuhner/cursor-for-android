package com.cursorforandroid.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.LocalScrollFadeSurface
import com.cursorforandroid.ui.navigation.WindowPosture
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/**
 * The right-side panel pinned beside the conversation on a wide window, the way cursor.com keeps it: a column of
 * [width] with a hairline on its start edge and a grip the reader drags to resize it. The width the drag lands on is
 * handed to [onResize] (in dp) once the finger lifts, so the shell can keep it; between, the pane follows the finger.
 * The column's bounds are the shell's business ([WindowPosture.clampPanelWidth]); this only reports.
 */
@Composable
fun PanelPane(
    width: Dp,
    onResize: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The finger's displacement since it came down, applied over [width] until the shell takes the new width.
    var dragPx by remember { mutableFloatStateOf(0f) }
    val basePx = with(density) { width.toPx() }
    val shownPx = (basePx + dragPx).coerceAtLeast(with(density) { WindowPosture.PANEL_MIN_DP.dp.toPx() })
    val dragState = rememberDraggableState { delta -> dragPx += if (rtl) delta else -delta }
    Row(modifier.fillMaxHeight().semantics { paneTitle = "Conversation panel" }.testTag("panel-pane")) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(GripWidth)
                .then(
                    if (onResize != null) {
                        Modifier
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Horizontal,
                                onDragStopped = {
                                    onResize(with(density) { shownPx.toDp() }.value.roundToInt())
                                    dragPx = 0f
                                },
                            )
                            .semantics { contentDescription = "Resize panel" }
                            .testTag("panel-grip")
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().width(CursorDimens.hairline).background(colors.strokeSubtle))
            if (onResize != null) Box(Modifier.width(3.dp).height(28.dp).background(colors.strokeStrong, CircleShape))
        }
        // On the canvas colour, as the web's panel is: the hairline in the grip is what marks the boundary. The surface
        // runs edge to edge; the panel's root consumes the window's insets (panelInsetPadding), the one consumption.
        Surface(color = colors.canvas, contentColor = colors.textPrimary, shape = RectangleShape, modifier = Modifier.fillMaxHeight().width(with(density) { shownPx.toDp() } - GripWidth)) {
            // The lists inside fade into the pane's own surface, as they do into the sheet's.
            CompositionLocalProvider(LocalScrollFadeSurface provides colors.canvas) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/** The grip's strip: the hairline in its middle, the drag over the whole of it. */
private val GripWidth = 10.dp
