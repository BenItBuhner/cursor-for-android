package com.cursorforandroid.ui.components

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cursorforandroid.ui.theme.CursorDimens

/**
 * The caption bar of a desktop window (Android desktop windowing, Samsung DeX on One UI 8, a freeform window), when
 * the app's own top chrome is drawn into it rather than under it — the way Chrome and Edge put their tabs there.
 *
 * The app asks for a transparent caption ([CaptionBarAppearance]); a system that grants it lays the window out from
 * its top edge, reports the bar as a `captionBar` inset, and reports where its own controls stand with
 * `WindowInsets.getBoundingRects` (API 35): the app menu at the start, minimize, maximize and close at the end. The
 * headers then stand in the bar's row ([captionRow]), kept clear of those controls by [clearance], and the buttons in
 * them claim their touches from the system ([captionControls]); everywhere else in the row the system still drags
 * the window, as it does over its own bar.
 *
 * Null (the [LocalCaptionBar] default) everywhere else: a phone, a tablet or a foldable full screen, a system that
 * keeps its caption opaque (an opaque caption is consumed before the app sees it, so the window reads no inset), or
 * one that reports no controls to stay clear of. The headers are then laid out exactly as they always were.
 *
 * [heightPx] is the bar's height from the window's top edge; [controls] are the system's controls in the window's
 * pixels, the same space `positionInWindow` answers in.
 */
@Immutable
class CaptionBar(val heightPx: Int, val controls: List<IntRect>) {

    /**
     * How far a row spanning [left] to [right] (window pixels) has to hold its content in from each end so none of it
     * stands under a system control: each control it overlaps counts against the end nearer the control's centre.
     */
    fun clearance(left: Float, right: Float): Clearance {
        var start = 0f
        var end = 0f
        val middle = (left + right) / 2
        for (control in controls) {
            if (control.right <= left || control.left >= right) continue
            if ((control.left + control.right) / 2f < middle) {
                start = maxOf(start, control.right - left)
            } else {
                end = maxOf(end, right - control.left)
            }
        }
        return Clearance(start, end)
    }

    /** Pixels to hold in from the window's left ([left]) and right ([right]) edges of a row. */
    data class Clearance(val left: Float, val right: Float)

    override fun equals(other: Any?): Boolean = other is CaptionBar && other.heightPx == heightPx && other.controls == controls

    override fun hashCode(): Int = 31 * heightPx + controls.hashCode()

    companion object {
        /**
         * The bar as the window reports it, or null where the headers keep their usual place: no caption inset, a
         * caption overlapping the status bar (an OEM reporting one full screen; Chrome's `CaptionBarInsetsRectProvider`
         * guards the same case), or no controls reported.
         */
        fun of(captionTopPx: Int, statusTopPx: Int, controls: List<IntRect>): CaptionBar? =
            if (captionTopPx <= 0 || statusTopPx > 0 || controls.isEmpty()) null else CaptionBar(captionTopPx, controls)
    }
}

/** The window's [CaptionBar] while the app's chrome is drawn into it; null everywhere else. */
val LocalCaptionBar = compositionLocalOf<CaptionBar?> { null }

/**
 * Reads the window's caption bar and provides it to [content]. Read again when the insets change (Compose's own
 * inset state) and whenever the window is resized, since the controls at the end move with the window's right edge
 * while the bar's height stays the same.
 */
@Composable
fun CaptionBarHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val captionTop = WindowInsets.captionBar.getTop(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bar = remember(captionTop, statusTop, size) {
        if (captionTop <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) null else CaptionBar.of(captionTop, statusTop, captionControls(view))
    }
    CompositionLocalProvider(LocalCaptionBar provides bar) {
        Box(Modifier.onSizeChanged { size = it }) { content() }
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun captionControls(view: View): List<IntRect> =
    view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar())
        ?.map { IntRect(it.left, it.top, it.right, it.bottom) }
        ?.filter { it.width > 0 && it.height > 0 }
        .orEmpty()

/**
 * Asks for a transparent caption, whose glyphs read on the theme: dark ones over a light theme. A request, not a
 * guarantee — [CaptionBarHost] finds out whether the system granted it — and nothing where there is no caption.
 */
@Composable
fun CaptionBarAppearance(activity: Activity, dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    SideEffect {
        val light = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or light,
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
        )
    }
}

/**
 * What a header pads its top by outside the caption bar: the status bar, and a caption the window reports without
 * the app's chrome in it (see [CaptionBar.of]), so nothing lands under it. On a phone the caption is empty and this
 * is the status bar alone, as it always was.
 */
val HeaderTopInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.captionBar)

/** The caption bar's height in [Dp], never less than a header button. */
fun CaptionBar.height(density: Density): Dp = with(density) { heightPx.toDp() }.coerceAtLeast(CursorDimens.iconButton)

/**
 * A header row standing in the caption bar: the bar's height, so its buttons are centred on the system's, and held
 * in from each end by as much as the system's controls take of it ([CaptionBar.clearance]), measured where the row
 * is in the window — so a rail header clears the app menu at the start, a chat header beside the rail clears
 * nothing, and one at the window's end clears the window controls.
 */
@Composable
fun Modifier.captionRow(bar: CaptionBar): Modifier {
    val density = LocalDensity.current
    var clearance by remember { mutableStateOf(CaptionBar.Clearance(0f, 0f)) }
    return this
        .onGloballyPositioned {
            val left = it.positionInWindow().x
            clearance = bar.clearance(left, left + it.size.width)
        }
        .heightIn(min = bar.height(density))
        .absolutePadding(left = with(density) { clearance.left.toDp() }, right = with(density) { clearance.right.toDp() })
}

/**
 * A group of header buttons in the caption bar claims its touches from the system, which otherwise drags the window
 * from anywhere in the bar. The claim reaches as far as the buttons' touch targets do past their glyphs; the row's
 * empty stretches claim nothing, so the window is still dragged by them.
 */
@Composable
fun Modifier.captionControls(bar: CaptionBar?): Modifier {
    if (bar == null) return this
    val reach = with(LocalDensity.current) { ((CursorDimens.touchTarget - CursorDimens.iconButton) / 2).toPx() }
    return systemGestureExclusion { coordinates ->
        Rect(-reach, -reach, coordinates.size.width + reach, coordinates.size.height + reach)
    }
}

/** A stretch of the caption bar's height that holds nothing of the app's, left for the window to be dragged by. */
@Composable
fun CaptionBarSpacer() {
    val bar = LocalCaptionBar.current ?: return
    Spacer(Modifier.fillMaxWidth().height(bar.height(LocalDensity.current)))
}
