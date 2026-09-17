package com.cursorforandroid.ui.media

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

/**
 * Registers the thumbnail about to be drawn for [src] with the app's viewer (none: a slot that belongs to nothing),
 * for as long as it is composed. [shape] is how its corners are rounded — the transform starts and ends on the same
 * radius — and [crop] whether it fills its box with the picture's middle rather than fitting the whole of it.
 */
@Composable
fun rememberThumbnailSlot(src: String, shape: CornerBasedShape, crop: Boolean): ThumbnailSlot {
    val viewer = LocalMediaViewer.current
    val density = LocalDensity.current
    val slot = remember(viewer, src) { viewer?.register(src) ?: ThumbnailSlot(0, src) }
    DisposableEffect(viewer, slot) {
        onDispose { viewer?.unregister(slot) }
    }
    // Plain fields: the radius and the fit can change with the theme, and nothing draws differently for it.
    val radius = shape.topStart.toPx(Size(ReferenceShapeSize, ReferenceShapeSize), density)
    SideEffect {
        slot.cornerRadius = radius
        slot.crop = crop
    }
    return slot
}

/**
 * Marks the node that is the thumbnail of [slot]: its position is kept current for the viewer to grow out of and
 * shrink into, and it draws nothing while the viewer stands in for it — the picture is on its way, or open — so that
 * the transform is one picture moving, not two. Put first in the chain so the whole box, border and all, is the one.
 */
fun Modifier.thumbnailSlot(slot: ThumbnailSlot): Modifier {
    if (slot.id == 0) return this
    return this
        .onGloballyPositioned { slot.coordinates = it }
        .drawWithContent {
            val viewer = slot.viewer
            if (viewer == null || !viewer.isHidden(slot)) drawContent()
        }
}

/** A corner size in dp does not care what it is a corner of; a proportional one gets this to be a corner of. */
private const val ReferenceShapeSize = 200f
