package com.cursorforandroid.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Drives the predictive back gesture the way the system does — started, progressed, then cancelled or committed —
 * through the window's [OnBackPressedDispatcher] (the activity's, or the sheet dialog's) and checks what is on
 * screen at each step. Robolectric's `pressBack` only exercises the legacy key path, which never reaches the
 * gesture code.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PredictiveBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Composable
    private fun App(graph: AppGraph) {
        var pending by remember { mutableStateOf<String?>(null) }
        CursorTheme(mode = ThemeMode.Dark) {
            CursorRoot(graph = graph, deepLinkAgentId = pending, onDeepLinkConsumed = { pending = null })
        }
    }

    @Before
    fun enterDemo() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }
        waitForText("Try the demo")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText(HOME_PLACEHOLDER, 30_000)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { onScreen(text) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** A gesture from the left edge, reported at each of [progress] on its way to the commit threshold. */
    private fun OnBackPressedDispatcher.swipe(vararg progress: Float) {
        dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
        compose.waitForIdle()
        progress.forEach {
            dispatchOnBackProgressed(BackEventCompat(it * 400f, 600f, it, BackEventCompat.EDGE_LEFT))
            compose.waitForIdle()
        }
    }

    private fun OnBackPressedDispatcher.release() {
        onBackPressed()
        compose.waitForIdle()
    }

    private fun OnBackPressedDispatcher.cancel() {
        dispatchOnBackCancelled()
        compose.waitForIdle()
    }

    /** Sheets are separate windows: their back events arrive through the sheet dialog's own dispatcher. */
    private fun sheetDispatcher(): OnBackPressedDispatcher = (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher

    private fun sheetShowing() = ShadowDialog.getLatestDialog()?.isShowing == true

    @Test
    fun `filter sheet drill-in is scrubbed by the gesture, rewinds on cancel and pops on commit`() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        waitForText(ROOT_PAGE)
        compose.onNodeWithText("Status").performClick()
        waitForText(STATUS_PAGE)
        compose.waitForIdle()
        assertThat(onScreen(ROOT_PAGE)).isFalse()
        val dispatcher = sheetDispatcher()

        // Half-way: the drill-in is on its way out and the root page is already coming in behind it.
        dispatcher.swipe(0.25f, 0.5f)
        assertThat(onScreen(STATUS_PAGE)).isTrue()
        assertThat(onScreen(ROOT_PAGE)).isTrue()
        assertThat(sheetShowing()).isTrue()

        // Cancelled: back on the drill-in page, sheet still up.
        dispatcher.cancel()
        assertThat(onScreen(STATUS_PAGE)).isTrue()
        assertThat(onScreen(ROOT_PAGE)).isFalse()
        assertThat(sheetShowing()).isTrue()

        // Committed: the page pops but the sheet stays. Material's own dismiss callback used to win here on
        // Android 13+ and close the whole sheet instead.
        dispatcher.swipe(0.6f)
        dispatcher.release()
        assertThat(onScreen(ROOT_PAGE)).isTrue()
        assertThat(onScreen(STATUS_PAGE)).isFalse()
        assertThat(sheetShowing()).isTrue()

        // At the root the gesture shrinks the sheet and, once committed, dismisses it.
        dispatcher.swipe(0.6f)
        assertThat(sheetShowing()).isTrue()
        dispatcher.release()
        compose.waitUntil(10_000) { !sheetShowing() }
        assertThat(onScreen(ROOT_PAGE)).isFalse()
    }

    @Test
    fun `destination pop is scrubbed by the gesture, rewinds on cancel and pops on commit`() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText(SETTINGS_PAGE)
        compose.waitForIdle()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()
        val dispatcher = compose.activity.onBackPressedDispatcher

        // Half-way: Settings recedes on top while Home is revealed underneath.
        dispatcher.swipe(0.25f, 0.5f)
        assertThat(onScreen(SETTINGS_PAGE)).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()

        dispatcher.cancel()
        assertThat(onScreen(SETTINGS_PAGE)).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()

        dispatcher.swipe(0.7f)
        dispatcher.release()
        waitForText(HOME_PLACEHOLDER)
        compose.waitForIdle()
        assertThat(onScreen(SETTINGS_PAGE)).isFalse()
    }

    @Test
    fun `a gesture that starts while the previous one is still rewinding takes the scene over`() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText(SETTINGS_PAGE)
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher

        dispatcher.swipe(0.3f)
        dispatcher.dispatchOnBackCancelled()
        // Drive the clock by hand: the next gesture starts while the rewind is mid-flight, and its finger then rests
        // long enough for the rewind to have finished before the first progress event. The rewind must not be
        // allowed to settle the scene underneath the new gesture.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(48)
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
        compose.mainClock.advanceTimeBy(400)
        dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 600f, 0.6f, BackEventCompat.EDGE_LEFT))
        compose.mainClock.advanceTimeBy(32)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // The second gesture owns the scene: both screens are still composed, the rewind did not settle Home away.
        assertThat(onScreen(SETTINGS_PAGE)).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()

        dispatcher.release()
        waitForText(HOME_PLACEHOLDER)
        compose.waitForIdle()
        assertThat(onScreen(SETTINGS_PAGE)).isFalse()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val ROOT_PAGE = "Grouping"
        const val STATUS_PAGE = "Archived"
        const val SETTINGS_PAGE = "Appearance"
    }
}
