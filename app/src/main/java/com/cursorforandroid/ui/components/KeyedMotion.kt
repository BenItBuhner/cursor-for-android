package com.cursorforandroid.ui.components

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec

/**
 * [this], a frame on from its start: a key's slide (Ctrl+B, Ctrl+Shift+B, Esc), which then moves in the first frame
 * drawn after the key. A slide set off between frames otherwise draws its first frame where it starts from and moves
 * only in the one after, which a key held to a desktop's instant answer reads as a beat of nothing; this one's first
 * frame is where it would have been a frame in, and it lands that frame sooner. Only the start moves: the path and its
 * easing are the slide's own.
 */
internal fun <T> FiniteAnimationSpec<T>.setOffAhead(): FiniteAnimationSpec<T> = Ahead(this)

private class Ahead<T>(private val spec: FiniteAnimationSpec<T>) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        VectorizedAhead(spec.vectorize(converter))
}

private class VectorizedAhead<V : AnimationVector>(private val spec: VectorizedFiniteAnimationSpec<V>) : VectorizedFiniteAnimationSpec<V> {
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        spec.getValueFromNanos(playTimeNanos + FrameNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        spec.getVelocityFromNanos(playTimeNanos + FrameNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        (spec.getDurationNanos(initialValue, targetValue, initialVelocity) - FrameNanos).coerceAtLeast(0L)
}

/** A frame at 60Hz; on a faster screen the first frame is a little further on still, and the slide no less smooth. */
private const val FrameNanos = 16_666_667L
