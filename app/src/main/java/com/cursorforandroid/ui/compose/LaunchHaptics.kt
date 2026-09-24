package com.cursorforandroid.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.rememberHaptics

/** Whether a new chat's launch was just refused: it stopped launching between [wasLaunching] and [launching], with an [error] to show for it. */
fun launchRefused(wasLaunching: Boolean, launching: Boolean, error: String?): Boolean = wasLaunching && !launching && error != null

/** Plays a [Haptic.Reject] as the New Chat composer's launch comes back refused (see [launchRefused]). */
@Composable
fun LaunchRefusedHaptic(state: NewAgentUiState) {
    val haptics = rememberHaptics()
    var wasLaunching by remember { mutableStateOf(state.isLaunching) }
    LaunchedEffect(state.isLaunching) {
        if (launchRefused(wasLaunching, state.isLaunching, state.error)) haptics.perform(Haptic.Reject)
        wasLaunching = state.isLaunching
    }
}
