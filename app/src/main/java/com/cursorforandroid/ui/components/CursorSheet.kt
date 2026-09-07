package com.cursorforandroid.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * The app's modal bottom sheet: elevated surface, sheet radius, no drag handle, opens straight to its full height,
 * runs edge-to-edge behind the navigation bar (content is inset), never taller than 86 % of the window, and handles
 * predictive back inside its own composition.
 *
 * Material's [ModalBottomSheet] registers a window-level back callback when its dialog attaches, after the dialog's
 * `OnBackPressedDispatcher` registered its own at the same priority; the later registration wins, so from Android 13
 * on a `BackHandler` placed inside the sheet is never reached and back always dismisses the whole sheet. That callback
 * is switched off here (`shouldDismissOnBackPress = false`) and replaced by a [PredictiveBackHandler] that reproduces
 * Material's shrink-toward-the-bottom-edge gesture animation, so handlers nested in [content] (drill-in pages) get the
 * gesture first, the way they do anywhere else in the app.
 *
 * [content] receives `dismiss`, which plays the hide animation before calling [onDismiss]; use it when a row is
 * picked so the sheet slides away instead of vanishing with the composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CursorSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    scrimColor: Color = Color.Black.copy(alpha = 0.5f),
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = CursorTheme.colors
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = remember(sheetState) {
        { scope.launch { sheetState.hide() }.invokeOnCompletion { currentOnDismiss() } }
    }

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
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
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
                dismiss()
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .graphicsLayer {
                    // Counter-scale so the content keeps its aspect ratio while the surface shrinks (Material does too).
                    val progress = backProgress.value
                    val sx = predictiveBackScale(progress, size.width, SheetMaxShrinkX.toPx())
                    val sy = predictiveBackScale(progress, size.height, SheetMaxShrinkY.toPx())
                    scaleY = if (sy != 0f) sx / sy else 1f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .navigationBarsPadding(),
        ) {
            content(dismiss)
        }
    }
}

/** Sheet title row: 16sp semibold title, optional leading / trailing controls, 48dp tall. */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(SheetHeaderHeight).padding(start = if (leading != null) 8.dp else 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = CursorTheme.typography.sectionTitle,
            color = CursorTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

val SheetHeaderHeight = 52.dp

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
