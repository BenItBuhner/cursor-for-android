package com.cursorforandroid.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * Where a pull to refresh stands against its threshold, as the finger moves it. [fraction] is the pull's
 * `distanceFraction`: 1 is far enough that letting go refreshes.
 */
class PullThreshold {
    var armed: Boolean = false
        private set

    /** From a [reset] to the reading after it. */
    private var wasReset = false

    /**
     * The finger took the pull to [fraction]: the threshold haptic if that crossed it either way. Read at exactly
     * the threshold straight after a [reset], it is the indicator where Material sprang it and holds it for a
     * refresh, between one animation and the next: armed, silently.
     */
    fun pulled(fraction: Float): Haptic? {
        val now = fraction >= 1f
        val resting = wasReset && fraction == 1f
        wasReset = false
        if (resting) {
            armed = true
            return null
        }
        if (now == armed) return null
        armed = now
        return if (now) Haptic.ThresholdActivate else Haptic.ThresholdDeactivate
    }

    /** The indicator is moving by itself (refreshing, springing back, hiding): the next pull starts from nothing, silently. */
    fun reset() {
        armed = false
        wasReset = true
    }
}

/**
 * Plays [state]'s threshold as the finger crosses it, both ways, the way the system's own pull-down lists do. Nothing
 * plays while the indicator animates or [isRefreshing] holds it, so the refresh itself and its end are silent — nor
 * in the frames between, where the indicator rests at the threshold with neither: the refresh asked for and not yet
 * under way, the refresh over and the indicator not yet hiding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshHaptics(state: PullToRefreshState, isRefreshing: Boolean) {
    val haptics = rememberHaptics()
    val refreshing by rememberUpdatedState(isRefreshing)
    LaunchedEffect(state, haptics) {
        val threshold = PullThreshold()
        snapshotFlow { if (state.isAnimating || refreshing) null else state.distanceFraction }.collect { fraction ->
            if (fraction == null) threshold.reset() else threshold.pulled(fraction)?.let(haptics::perform)
        }
    }
}
