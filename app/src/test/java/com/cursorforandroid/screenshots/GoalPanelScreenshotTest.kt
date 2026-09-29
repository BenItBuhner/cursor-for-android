package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.ui.conversation.GoalPanelHarness
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The goal panel opened onto a very long objective, in the chat's dock ([GoalPanelHarness]): closed, the one-line strip
 * over a queued follow-up and the composer; opened, grown to 8dp under the header and scrolled at its top (the bottom
 * fading), in its middle (both edges) and at its end (the top); with the keyboard up; and on a wide window, where the
 * dock keeps the composer's width cap, with twice the objective so it still overflows. Same device qualifiers as [AppScreenshotTest], clock pinned.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class GoalPanelScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun pinClock() {
        AppClock.nowMillis = { GoalPanelHarness.T0 + 4_512_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(harness: GoalPanelHarness, goal: Goal = GoalPanelHarness.LongGoal) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { harness.Content(goal = goal) }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private val range get() = compose.onNodeWithTag("goal-strip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollTo(fraction: Float) {
        val r = checkNotNull(range)
        compose.onNodeWithTag("goal-strip").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, r.maxValue() * fraction - r.value()) }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        assertThat(range!!.value()).isWithin(1f).of(r.maxValue() * fraction)
    }

    @Test
    fun closed() {
        show(GoalPanelHarness())
        assertThat(range?.maxValue() ?: 0f).isEqualTo(0f)
        capture("770_goal_panel_closed")
    }

    @Test
    fun openedAtTheTopMiddleAndEnd() {
        show(GoalPanelHarness(open = true))
        assertThat(range!!.maxValue()).isGreaterThan(0f)
        capture("771_goal_panel_open_top")
        scrollTo(0.5f)
        capture("772_goal_panel_open_middle")
        scrollTo(1f)
        capture("773_goal_panel_open_end")
    }

    @Test
    fun openedWithTheKeyboardUp() {
        show(GoalPanelHarness(open = true, keyboard = 290.dp))
        assertThat(range!!.maxValue()).isGreaterThan(0f)
        capture("774_goal_panel_open_keyboard")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun openedOnAWideWindow() {
        // Twice the objective: the column's 640dp holds the once-over in the page.
        show(GoalPanelHarness(open = true), goal = GoalPanelHarness.LongGoal.copy(objective = GoalPanelHarness.LongObjective + " " + GoalPanelHarness.LongObjective))
        assertThat(range!!.maxValue()).isGreaterThan(0f)
        capture("775_goal_panel_open_wide")
    }
}
