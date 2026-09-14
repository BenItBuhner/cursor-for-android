package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeps whatever it wraps — the follow-up composer — resting on the top edge of the keyboard while there is one, and
 * on the navigation bar otherwise, with a single inset consumption for both.
 *
 * The two insets are one [union] rather than `navigationBarsPadding().imePadding()` in sequence. The union is the
 * larger of the two on each side, so the composer sits on `max(navigationBar, ime)` at every frame: on the bar while
 * the keyboard is hidden, and — as the keyboard's edge crosses the bar on its way up or down — on the keyboard the
 * moment it is the higher of the two, without a frame in which both are added or neither is. The chained form gets
 * the same answer through the padding modifiers' consumption bookkeeping (`ime` minus what `navigationBars` already
 * took); one modifier reads both insets in a single measure pass and consumes both for anything nested inside, so
 * a child cannot pad for the keyboard a second time.
 *
 * The keyboard's inset is the animated one: from Android 11 the platform reports it through
 * `WindowInsetsAnimation` every frame the keyboard moves — its own show and hide, and the Android 15+ predictive
 * back scrub that lets the finger drag it down — and Compose relays each value to the padding, so the composer
 * rides the keyboard's edge rather than jumping to where it will end up. That only holds while the window is
 * edge-to-edge (`WindowCompat.setDecorFitsSystemWindows(window, false)`, which the theme applies) and declares
 * `adjustResize`: with the decor fitting system windows the platform declines to animate the predictive dismiss and
 * hides the keyboard in one step instead.
 */
@Composable
fun Modifier.keyboardInsetPadding(): Modifier = this.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
