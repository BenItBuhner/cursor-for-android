package com.cursorforandroid.ui.panel

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.CursorDrawer
import com.cursorforandroid.ui.components.CursorDrawerState
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.constant
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bennett: an edge swipe opening the sidebar drawer or the side panel was only felt after a long or slow drag, never
 * with the short quick flicks he uses. Each sheet now plays one haptic per open or close, as it lands on the other side
 * from where it rested, whatever carried it there: the finger, or the fling after a flick that barely moved it. A drag
 * that springs back plays nothing, and neither does an open or close without a finger.
 *
 * The scene is the chat's shape, as in [SidePanelSwipeTest]: the drawer around a panel host around a transcript. Every
 * drag is stepped at the test clock's 16 ms frame; 300 px/s is under the 400 dp/s fling threshold, 4 000 px/s well over.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SheetLandingHapticsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recorder = RecordingView(ApplicationProvider.getApplicationContext())

    private lateinit var scope: CoroutineScope

    private val landed = Haptic.GestureEnd.constant()

    /** Shows the scene; each haptic is recorded with how far open [sheet] reports it is as it plays. */
    private fun show(panel: SidePanelState, drawer: CursorDrawerState, sheet: () -> Float) {
        recorder.sheet = sheet
        compose.setContent {
            scope = rememberCoroutineScope()
            val view = LocalView.current
            CursorTheme(mode = ThemeMode.Dark) {
                // The sheets play through the recorder; everything inside them keeps the window's own view.
                CompositionLocalProvider(LocalView provides recorder) {
                    CursorDrawer(
                        state = drawer,
                        drawerWidth = DrawerWidth,
                        drawerContent = { CompositionLocalProvider(LocalView provides view) { Box(Modifier.fillMaxSize().testTag("drawer-body")) } },
                    ) {
                        SidePanelHost(
                            state = panel,
                            panelWidth = PanelWidth,
                            panelContent = { CompositionLocalProvider(LocalView provides view) { Box(Modifier.fillMaxSize().testTag("panel-body")) } },
                        ) {
                            CompositionLocalProvider(LocalView provides view) {
                                LazyColumn(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("transcript")) {
                                    items(12) { Text("Reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp).testTag("reply-$it")) }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showDrawer(value: DrawerValue): CursorDrawerState {
        val drawer = CursorDrawerState(value)
        show(SidePanelState(SidePanelValue.Closed), drawer) { drawer.fraction }
        return drawer
    }

    private fun showPanel(value: SidePanelValue): SidePanelState {
        val panel = SidePanelState(value)
        show(panel, CursorDrawerState(DrawerValue.Closed)) { panel.fraction }
        return panel
    }

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    private val windowWidth: Float get() = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

    private val replyY: Float get() = compose.onNodeWithTag("reply-2").fetchSemanticsNode().boundsInRoot.center.y

    /** A finger at [from] (in the window) that travels each of [legs] in turn at [pxPerSecond], one move a frame, then lifts unless [lift] is off. */
    private fun swipe(from: Offset, vararg legs: Float, pxPerSecond: Float, lift: Boolean = true) {
        compose.onRoot().performTouchInput {
            down(from)
            legs.forEach { travel(it, pxPerSecond) }
            if (lift) up()
        }
        compose.waitForIdle()
    }

    private fun TouchInjectionScope.travel(dx: Float, pxPerSecond: Float) {
        val frames = (abs(dx) / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
        repeat(frames) { moveBy(Offset(dx / frames, 0f), delayMillis = FrameMillis) }
    }

    private fun lift() {
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    /** Where the drawer's drags start: on the transcript, well clear of the left edge's back strip. */
    private val drawerStart: Offset get() = Offset(windowWidth * 0.3f, replyY)

    /** Where the panel's drags start: on the transcript toward the right, clear of the right edge's back strip. */
    private val panelStart: Offset get() = Offset(windowWidth * 0.75f, replyY)

    /** The haptics played so far, each as its constant and how far open the sheet was. */
    private val played: List<Pair<Int, Float>> get() = recorder.played

    private fun assertLandedOnceAt(end: Float) {
        assertThat(played.map { it.first }).containsExactly(landed)
        assertThat(played.single().second).isWithin(0.011f).of(end)
    }

    private fun assertRestsAt(drawer: CursorDrawerState, value: DrawerValue) {
        assertThat(drawer.targetValue).isEqualTo(value)
        assertThat(drawer.fraction).isWithin(0.001f).of(if (value == DrawerValue.Open) 1f else 0f)
    }

    private fun assertRestsAt(panel: SidePanelState, value: SidePanelValue) {
        assertThat(panel.targetValue).isEqualTo(value)
        assertThat(panel.fraction).isWithin(0.001f).of(if (value == SidePanelValue.Open) 1f else 0f)
    }

    // -- the sidebar drawer, from the left ---------------------------------------------------------------------------

    @Test
    fun `a short quick flick opens the drawer and is felt once as it lands open`() {
        val drawer = showDrawer(DrawerValue.Closed)
        swipe(drawerStart, px(DrawerWidth) * 0.15f, pxPerSecond = 4_000f, lift = false)
        // The finger has barely moved the sheet: nothing yet, and nowhere near half-way.
        assertThat(drawer.fraction).isLessThan(0.2f)
        assertThat(played).isEmpty()
        lift()
        assertRestsAt(drawer, DrawerValue.Open)
        assertLandedOnceAt(1f)
    }

    @Test
    fun `a short quick flick closes the drawer and is felt once as it lands shut`() {
        val drawer = showDrawer(DrawerValue.Open)
        swipe(Offset(windowWidth * 0.9f, replyY), -px(DrawerWidth) * 0.15f, pxPerSecond = 4_000f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertLandedOnceAt(0f)
    }

    @Test
    fun `a slow drag across is felt once as the drawer lands, not as the finger passes half-way`() {
        val drawer = showDrawer(DrawerValue.Closed)
        swipe(drawerStart, px(DrawerWidth) * 0.7f, pxPerSecond = 300f, lift = false)
        assertThat(drawer.fraction).isGreaterThan(0.5f)
        assertThat(played).isEmpty()
        lift()
        assertRestsAt(drawer, DrawerValue.Open)
        assertLandedOnceAt(1f)

        recorder.played.clear()
        swipe(Offset(windowWidth * 0.9f, replyY), -px(DrawerWidth) * 0.7f, pxPerSecond = 300f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertLandedOnceAt(0f)
    }

    @Test
    fun `a drawer drag that springs back is never felt, even one taken past half-way and back`() {
        val drawer = showDrawer(DrawerValue.Closed)
        swipe(drawerStart, px(DrawerWidth) * 0.3f, pxPerSecond = 300f)
        assertRestsAt(drawer, DrawerValue.Closed)
        swipe(drawerStart, px(DrawerWidth) * 0.7f, -px(DrawerWidth) * 0.5f, pxPerSecond = 300f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(played).isEmpty()
    }

    @Test
    fun `the drawer opened and closed without a finger is not felt, and the next flick still is, once`() {
        val drawer = showDrawer(DrawerValue.Closed)
        compose.runOnIdle { scope.launch { drawer.open() } }
        compose.waitForIdle()
        assertRestsAt(drawer, DrawerValue.Open)
        compose.runOnIdle { drawer.jumpTo(DrawerValue.Closed, scope) }
        compose.waitForIdle()
        compose.runOnIdle { drawer.jumpTo(DrawerValue.Open, scope) }
        compose.waitForIdle()
        assertThat(played).isEmpty()

        swipe(Offset(windowWidth * 0.9f, replyY), -px(DrawerWidth) * 0.15f, pxPerSecond = 4_000f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertLandedOnceAt(0f)
    }

    // -- the side panel, from the right ------------------------------------------------------------------------------

    @Test
    fun `a short quick flick opens the panel and is felt once as it lands open`() {
        val panel = showPanel(SidePanelValue.Closed)
        swipe(panelStart, -px(PanelWidth) * 0.15f, pxPerSecond = 4_000f, lift = false)
        assertThat(panel.fraction).isLessThan(0.2f)
        assertThat(played).isEmpty()
        lift()
        assertRestsAt(panel, SidePanelValue.Open)
        assertLandedOnceAt(1f)
    }

    @Test
    fun `a short quick flick closes the panel and is felt once as it lands shut`() {
        val panel = showPanel(SidePanelValue.Open)
        compose.onNodeWithTag("panel-body").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            travel(px(PanelWidth) * 0.15f, pxPerSecond = 4_000f)
            up()
        }
        compose.waitForIdle()
        assertRestsAt(panel, SidePanelValue.Closed)
        assertLandedOnceAt(0f)
    }

    @Test
    fun `a slow drag across is felt once as the panel lands, not as the finger passes half-way`() {
        val panel = showPanel(SidePanelValue.Closed)
        swipe(panelStart, -px(PanelWidth) * 0.7f, pxPerSecond = 300f, lift = false)
        assertThat(panel.fraction).isGreaterThan(0.5f)
        assertThat(played).isEmpty()
        lift()
        assertRestsAt(panel, SidePanelValue.Open)
        assertLandedOnceAt(1f)

        recorder.played.clear()
        compose.onNodeWithTag("panel-body").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            travel(px(PanelWidth) * 0.7f, pxPerSecond = 300f)
            up()
        }
        compose.waitForIdle()
        assertRestsAt(panel, SidePanelValue.Closed)
        assertLandedOnceAt(0f)
    }

    @Test
    fun `a panel drag that springs back is never felt, even one taken past half-way and back`() {
        val panel = showPanel(SidePanelValue.Closed)
        swipe(panelStart, -px(PanelWidth) * 0.3f, pxPerSecond = 300f)
        assertRestsAt(panel, SidePanelValue.Closed)
        swipe(panelStart, -px(PanelWidth) * 0.7f, px(PanelWidth) * 0.5f, pxPerSecond = 300f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(played).isEmpty()
    }

    @Test
    fun `the panel opened and closed without a finger is not felt, and a flick open then shut is felt twice, once each`() {
        val panel = showPanel(SidePanelValue.Closed)
        compose.runOnIdle { scope.launch { panel.open() } }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { panel.close() } }
        compose.waitForIdle()
        compose.runOnIdle { panel.jumpTo(SidePanelValue.Open, scope) }
        compose.waitForIdle()
        compose.runOnIdle { panel.jumpTo(SidePanelValue.Closed, scope) }
        compose.waitForIdle()
        assertThat(played).isEmpty()

        swipe(panelStart, -px(PanelWidth) * 0.15f, pxPerSecond = 4_000f)
        assertRestsAt(panel, SidePanelValue.Open)
        compose.onNodeWithTag("panel-body").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            travel(px(PanelWidth) * 0.15f, pxPerSecond = 4_000f)
            up()
        }
        compose.waitForIdle()
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(played.map { it.first }).containsExactly(landed, landed)
        assertThat(played[0].second).isWithin(0.011f).of(1f)
        assertThat(played[1].second).isWithin(0.011f).of(0f)
    }

    /** A view that keeps every haptic it is asked for, with how far open the sheet under test was as it played. */
    private class RecordingView(context: Context) : View(context) {
        var sheet: () -> Float = { Float.NaN }
        val played = mutableListOf<Pair<Int, Float>>()

        init {
            isHapticFeedbackEnabled = true
        }

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant to sheet()
            return true
        }
    }

    private companion object {
        const val FrameMillis = 16L
        val DrawerWidth = 300.dp
        val PanelWidth = 360.dp
    }
}
