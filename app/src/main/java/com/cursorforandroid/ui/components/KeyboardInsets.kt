package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cursorforandroid.ui.theme.CursorDimens

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

/**
 * Docks the follow-up composer (with the queue and goal strips stacked over it) at the bottom of a chat:
 * [CursorDimens.composerGutter] of air at each side of the window, and under the box [CursorDimens.composerBottomGap]
 * on top of the keyboard-or-navigation-bar inset ([keyboardInsetPadding]).
 *
 * The inset is added under the gap rather than traded against it. With `max(gutter, inset)` the box would sit
 * `gutter` above the window's edge on a device that hides its bar but flush against a three-button bar, and the gap
 * the eye reads — to the bar, to the keyboard, or to the edge when there is neither — would differ by mode. Added,
 * that gap is the same everywhere: on a gesture bar the box stands above the bar's 24dp region with the home handle
 * in it, on three buttons above their 48dp, over a keyboard above its top edge, and on a hidden bar above the
 * display's edge, [CursorDimens.composerBottomGap] each time. Because the inset is the union of the two, the
 * keyboard never adds the bar's height a second time.
 */
@Composable
fun Modifier.composerDockPadding(): Modifier = this
    .padding(horizontal = CursorDimens.composerGutter)
    .padding(bottom = CursorDimens.composerBottomGap)
    .keyboardInsetPadding()
