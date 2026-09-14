package com.cursorforandroid.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a composer docked with [keyboardInsetPadding] sits for the insets the platform reports, frame by frame. The
 * insets are dispatched to the Compose view the way the window does it, with the values an Android 15+ keyboard
 * animation passes through: the show, the predictive-back scrub that lets the keyboard peek down by a tenth of its
 * height and come back on a cancel, and the commit that hides it. The expectations are the reference behaviour of
 * Google Messages with Gboard: the composer's bottom is on the keyboard's top edge on every frame the keyboard is the
 * higher of the two insets and on the navigation bar otherwise, never a frame early or late, and the newest turn of
 * a bottom-anchored transcript keeps its place against the composer throughout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class KeyboardInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var density = 1f

    /** A transcript-shaped screen: a bottom-anchored list taking what is left above a docked composer. */
    @Composable
    private fun Host(rows: Int = 40) {
        density = LocalDensity.current.density
        CursorTheme(mode = ThemeMode.Dark) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = rememberLazyListState(),
                        reverseLayout = true,
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("list"),
                    ) {
                        items(rows, key = { it }) { index -> Box(Modifier.fillMaxWidth().height(80.dp).testTag("row-$index")) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom = ComposerGap).keyboardInsetPadding().testTag("composer")) {
                    // A child padding for the keyboard again must find nothing left to pad: the dock consumed it.
                    Box(Modifier.fillMaxWidth().height(ComposerHeight).imePadding().testTag("inner"))
                }
            }
        }
    }

    /** The window's insets as the platform reports them: a navigation bar, and a keyboard [ime] px tall. */
    private fun dispatchInsets(navigationBar: Int, ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    /** The Compose host view inside the activity: the one Compose installed its insets listener on. */
    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun rootHeight(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    private fun px(dp: androidx.compose.ui.unit.Dp): Float = dp.value * density

    /** Where the composer's bottom edge is expected for these insets: [ComposerGap] above the higher of the two. */
    private fun expectedComposerBottom(navigationBar: Int, ime: Int): Float = rootHeight() - maxOf(navigationBar, ime) - px(ComposerGap)

    private fun assertDockedFor(navigationBar: Int, ime: Int) {
        val composer = bounds("composer")
        assertThat(composer.bottom).isWithin(0.5f).of(expectedComposerBottom(navigationBar, ime))
        assertThat(composer.height).isWithin(0.5f).of(px(ComposerHeight))
        // Single consumption: the child asked for keyboard padding and got none, so it fills the composer exactly.
        assertThat(bounds("inner")).isEqualTo(composer)
        // Bottom anchoring: the newest row's bottom is on the composer's top, whatever the viewport's height.
        assertThat(bounds("row-0").bottom).isWithin(0.5f).of(composer.top)
    }

    @Test
    fun `composer rests on the navigation bar while the keyboard is hidden`() {
        compose.setContent { Host() }
        dispatchInsets(navigationBar = NavigationBar, ime = 0)
        assertDockedFor(NavigationBar, 0)
    }

    @Test
    fun `composer rides the keyboard's edge on every frame of the show and the hide`() {
        compose.setContent { Host() }
        dispatchInsets(NavigationBar, 0)
        // The keyboard's inset climbs through the navigation bar's height on its way up; the composer stays on the bar
        // until the keyboard is the higher of the two, then on the keyboard, with no frame in between that adds both.
        for (ime in showFrames()) {
            dispatchInsets(NavigationBar, ime)
            assertDockedFor(NavigationBar, ime)
        }
        for (ime in showFrames().asReversed()) {
            dispatchInsets(NavigationBar, ime)
            assertDockedFor(NavigationBar, ime)
        }
        dispatchInsets(NavigationBar, 0)
        assertDockedFor(NavigationBar, 0)
    }

    @Test
    fun `predictive back scrubs the keyboard down by a tenth, rewinds on cancel and lands on the navigation bar on commit`() {
        compose.setContent { Host() }
        dispatchInsets(NavigationBar, Keyboard)
        assertDockedFor(NavigationBar, Keyboard)

        // The platform's ImeBackAnimationController: the gesture moves the keyboard by at most PEEK_FRACTION (0.1) of its
        // height, and the app's insets follow the finger.
        val peek = (Keyboard * 0.1f).toInt()
        for (step in 1..6) {
            val ime = Keyboard - peek * step / 6
            dispatchInsets(NavigationBar, ime)
            assertDockedFor(NavigationBar, ime)
        }
        // Cancelled: back up over a few frames.
        for (step in 5 downTo 0) {
            val ime = Keyboard - peek * step / 6
            dispatchInsets(NavigationBar, ime)
            assertDockedFor(NavigationBar, ime)
        }
        assertDockedFor(NavigationBar, Keyboard)

        // Committed: the post-commit hide runs the rest of the way down; the composer ends on the navigation bar with
        // its gap intact, and the newest turn is still on it.
        for (ime in showFrames().asReversed()) {
            dispatchInsets(NavigationBar, ime)
            assertDockedFor(NavigationBar, ime)
        }
        dispatchInsets(NavigationBar, 0)
        assertDockedFor(NavigationBar, 0)
    }

    @Test
    fun `a short transcript reads from the top and is not dragged down by the keyboard leaving`() {
        compose.setContent { Host(rows = 2) }
        dispatchInsets(NavigationBar, 0)
        val restingTop = bounds("row-1").top
        assertThat(restingTop).isWithin(0.5f).of(0f)
        dispatchInsets(NavigationBar, Keyboard)
        // Two rows fit above the keyboard, so they stay where they were: the list wraps its content at the top.
        assertThat(bounds("row-1").top).isWithin(0.5f).of(restingTop)
        assertThat(bounds("composer").bottom).isWithin(0.5f).of(expectedComposerBottom(NavigationBar, Keyboard))
        dispatchInsets(NavigationBar, 0)
        assertThat(bounds("row-1").top).isWithin(0.5f).of(restingTop)
    }

    /** The show animation's inset values, coarsely: a decelerating climb from 0 to the keyboard's full height. */
    private fun showFrames(): List<Int> = (1..12).map { step ->
        val t = step / 12f
        (Keyboard * (1f - (1f - t) * (1f - t))).toInt()
    }

    private companion object {
        /** 48dp at 420dpi, the gesture navigation bar of the reference device. */
        const val NavigationBar = 126
        /** A Gboard of the reference recording's proportions on this window. */
        const val Keyboard = 900
        val ComposerGap = 10.dp
        val ComposerHeight = 56.dp
    }
}
