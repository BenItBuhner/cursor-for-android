package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * How far in from the composer's sides a card docked over it stands, so that the card's corners are concentric with
 * the composer's. The composer bends [CursorDimens.composerRadius] (24dp) in from each side; a card with [shape]'s
 * smaller corners set flush with the composer's sides would bend only its own radius in, and the two straight runs —
 * the flat top of the composer and the flat bottom of the card — would end at different points, one curve starting
 * inside the other (Bennett's 2026-09-20 frame: three queued cards over the composer, each corner pinching in
 * before the composer's does). Inset by the difference between the two radii, the card's flat edge starts and ends
 * exactly where the composer's does, and each of its arcs is centred on the same vertical as the arc beneath it,
 * which is what makes a stack of cards read as one family over the box rather than as smaller boxes on top of it.
 *
 * Read from the tokens at composition, not written down as a number: a change to the composer's radius or to the
 * card's shape moves the inset with it. The card's bottom corners are the ones that face the composer, so those are
 * the ones read, start and end apart in case a shape rounds them differently. A card whose corners are as round as
 * the composer's, or rounder, stands flush. The corner is resolved as a dp corner is — it does not care what it is
 * a corner of — and a proportional one is given a reference square to be a corner of, as the thumbnails do.
 */
@Composable
fun composerDockInset(shape: CornerBasedShape): DockInset {
    val density = LocalDensity.current
    val reference = Size(ReferenceShapeSize, ReferenceShapeSize)
    return with(density) {
        DockInset(
            start = (CursorDimens.composerRadius - shape.bottomStart.toPx(reference, density).toDp()).coerceAtLeast(0.dp),
            end = (CursorDimens.composerRadius - shape.bottomEnd.toPx(reference, density).toDp()).coerceAtLeast(0.dp),
        )
    }
}

/** The inset [composerDockInset] derives, at each side of a docked card. */
data class DockInset(val start: Dp, val end: Dp)

/**
 * The surface of a card docked over the follow-up composer — a queued follow-up, the goal strip, a notice about the
 * transcript's load: the composer's own surface and stroke in [shape], stood in from the composer's sides by
 * [composerDockInset] so its corners are concentric with the composer's. Any number of them stack over the box, each
 * inset the same, with whatever gap the stack keeps between them; a lone card is placed no differently.
 */
@Composable
fun Modifier.dockedCard(
    shape: CornerBasedShape = CursorTheme.shapes.xl,
    fill: Color = CursorTheme.colors.elevated,
    border: Color = CursorTheme.colors.strokeSubtle,
): Modifier {
    val inset = composerDockInset(shape)
    return this.padding(start = inset.start, end = inset.end).cursorSurface(fill, border, shape)
}

/** A corner size in dp does not care what it is a corner of; a proportional one gets this to be a corner of. */
private const val ReferenceShapeSize = 200f
