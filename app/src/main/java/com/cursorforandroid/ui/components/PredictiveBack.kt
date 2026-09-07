package com.cursorforandroid.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The curve Material applies to raw back-gesture progress before it drives a predictive-back transform (drawer,
 * bottom sheet): front-loaded so the surface reacts as soon as the finger moves, then eases into the end state.
 */
val PredictiveBackEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * Undoes a transition that was being scrubbed by a back gesture the user then cancelled: plays the fraction back
 * to zero over a duration proportional to how far it got, then snaps to [SeekableTransitionState.currentState].
 * The same recipe the navigation host uses to rewind a cancelled pop, so drill-ins inside a sheet rewind exactly
 * like destinations do.
 */
suspend fun <S> SeekableTransitionState<S>.rewind(totalDurationMillis: Int) {
    val start = fraction
    if (start > 0f) {
        coroutineScope {
            animate(start, 0f, animationSpec = tween((start * totalDurationMillis).toInt())) { value, _ ->
                launch { seekTo(value) }
            }
        }
    }
    snapTo(currentState)
}
