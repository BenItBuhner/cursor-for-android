package com.cursorforandroid.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * Where the New Chat pane's content begins. Short of the pane's height, far enough down that the composer and what it
 * lists sit as one block in the middle; taller, at the top, scrolling from there.
 *
 * The room above is a lead item of its own ahead of the composer ([Lead]), so everything under it moves with it. As
 * the list loads or changes, the block glides to its new place, and what came in is already in its place under the
 * composer. As the pane itself resizes (the keyboard rising, a window resized), the block keeps to the middle frame by
 * frame, which the keyboard's own animation makes smooth; a glide would lag behind it and leave the composer under the
 * keyboard on the way.
 *
 * Until a first layout says how tall the block is, [arrangement] centres it instead, which draws it where the lead then
 * puts it.
 */
@Stable
internal class PageCentring {
    private var lead: Animatable<Float, AnimationVector1D>? by mutableStateOf(null)

    /** Read as the list lays out rather than as it composes, so that it and [Lead] never disagree in any one layout. */
    val arrangement: Arrangement.Vertical = object : Arrangement.Vertical {
        override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
            with(if (lead == null) Arrangement.Center else Arrangement.Top) { arrange(totalSize, sizes, outPositions) }
        }
    }

    /** The room above the composer; the list's first item, keyed [LEAD_KEY], ahead of the item keyed [COMPOSER_KEY]. */
    @Composable
    fun Lead() {
        Layout(Modifier) { _, _ -> layout(0, lead?.value?.roundToInt() ?: 0) {} }
    }

    /** Keeps the lead to [list]'s layouts for as long as it is called. */
    suspend fun follow(list: LazyListState) {
        var pane = Int.MIN_VALUE
        snapshotFlow { list.layoutInfo.takeIf { it.totalItemsCount > 0 }?.let { centredLead(it) to it.viewportSize.height } }.collectLatest { laidOut ->
            val (target, height) = laidOut ?: return@collectLatest
            val lead = lead
            val resized = height != pane
            pane = height
            when {
                lead == null -> this.lead = Animatable(target)
                resized -> lead.snapTo(target)
                else -> lead.animateTo(target, Glide)
            }
        }
    }

    companion object {
        const val LEAD_KEY = "lead"
        const val COMPOSER_KEY = "composer"

        private val Glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/**
 * The lead that centres the composer and all under it in the room between the list's content padding. While the end of
 * the list is out of sight, the block is at least as tall as what shows of it from the composer down, so the lead is
 * at most half the composer's offset; each layout on the way there sees more of it, until the end shows or the lead is
 * none.
 */
internal fun centredLead(info: LazyListLayoutInfo): Float {
    val room = info.viewportEndOffset - info.afterContentPadding
    val items = info.visibleItemsInfo
    val composer = items.firstOrNull { it.key == PageCentring.COMPOSER_KEY } ?: return 0f
    val last = items.last()
    if (last.index == info.totalItemsCount - 1) return ((room - (last.offset + last.size - composer.offset)) / 2f).coerceAtLeast(0f)
    // Rounded down: a lead of a pixel drawn is a composer a pixel down, and half of it rounded to the nearest is that pixel again.
    return (composer.offset / 2).coerceAtLeast(0).toFloat()
}
