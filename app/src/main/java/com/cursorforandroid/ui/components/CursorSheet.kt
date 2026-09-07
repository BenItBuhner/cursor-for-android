package com.cursorforandroid.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * The app's modal bottom sheet: elevated surface, sheet radius, no drag handle, and predictive back handled inside
 * the sheet's own composition.
 *
 * Material's [ModalBottomSheet] registers a window-level back callback when its dialog attaches, after the dialog's
 * `OnBackPressedDispatcher` registered its own at the same priority; the later registration wins, so from Android 13
 * on a `BackHandler` placed inside the sheet is never reached and back always dismisses the whole sheet. That callback
 * is switched off here (`shouldDismissOnBackPress = false`) and replaced by a [PredictiveBackHandler] that reproduces
 * Material's shrink-toward-the-bottom-edge gesture animation, so handlers nested in [content] (drill-in pages) get the
 * gesture first, the way they do anywhere else in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CursorSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CursorTheme.colors
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.graphicsLayer {
            val progress = backProgress.value
            scaleX = predictiveBackScale(progress, size.width, SheetMaxShrinkX.toPx())
            scaleY = predictiveBackScale(progress, size.height, SheetMaxShrinkY.toPx())
            // Anchor at the bottom of the window, as Material does, so the sheet shrinks up from its resting edge.
            val top = sheetState.offsetOrNull() ?: return@graphicsLayer
            transformOrigin = TransformOrigin(0.5f, (top + size.height) / size.height)
        },
        sheetState = sheetState,
        containerColor = colors.elevated,
        contentColor = colors.textPrimary,
        shape = CursorTheme.shapes.sheet,
        dragHandle = null,
        scrimColor = scrimColor,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        PredictiveBackHandler { events ->
            try {
                events.collect { backProgress.snapTo(PredictiveBackEasing.transform(it.progress)) }
            } catch (e: CancellationException) {
                scope.launch { backProgress.animateTo(0f) }
                return@PredictiveBackHandler
            }
            // Committed. Like Material, a fully expanded sheet that also has a half-height anchor collapses to it
            // first; otherwise hide, keeping the shrink in place for the hide animation.
            if (sheetState.currentValue == SheetValue.Expanded && sheetState.hasPartiallyExpandedState) {
                scope.launch { backProgress.animateTo(0f) }
                scope.launch { sheetState.partialExpand() }
            } else {
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                // Counter-scale so the content keeps its aspect ratio while the surface shrinks (Material does too).
                val progress = backProgress.value
                val sx = predictiveBackScale(progress, size.width, SheetMaxShrinkX.toPx())
                val sy = predictiveBackScale(progress, size.height, SheetMaxShrinkY.toPx())
                scaleY = if (sy != 0f) sx / sy else 1f
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        ) {
            content()
        }
    }
}

/** Material's sheet shrink: at full progress the extent loses [maxDistancePx] (or all of it, if smaller). */
private fun predictiveBackScale(progress: Float, extent: Float, maxDistancePx: Float): Float =
    if (extent.isNaN() || extent == 0f) 1f else 1f - min(maxDistancePx, extent) * progress / extent

@OptIn(ExperimentalMaterial3Api::class)
private fun SheetState.offsetOrNull(): Float? = try {
    requireOffset()
} catch (e: IllegalStateException) {
    null
}

private val SheetMaxShrinkX = 48.dp
private val SheetMaxShrinkY = 24.dp
