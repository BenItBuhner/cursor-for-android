package com.cursorforandroid.ui.panel

import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.BackEdgeMinWidth
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.CursorDrawer
import com.cursorforandroid.ui.components.CursorDrawerState
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bennett's bug: with the composer focused and the keyboard up, swiping open the sidebar drawer or the side panel left
 * the composer focused and the keyboard standing over the sheet. Opening either side — by a swipe or by its button —
 * now lets the composer go and puts the keyboard away, once the open is committed: never while the finger is still
 * dragging the sheet in, so a swipe that settles back shut, or that the system's back gesture takes away (#321), leaves
 * the keyboard where it was without so much as a flicker. The composer's draft stays; a field in the sheet itself that
 * holds focus as it opens keeps it, and the keyboard with it.
 *
 * The scene is the chat's shape, as in [BackEdgeSwipeTest]: the drawer around a panel host around a transcript, with
 * the real composer docked under it and the header's two buttons over it. Every swipe starts from the window's
 * coordinates and moves once a 16 ms frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SheetKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var draft by mutableStateOf("")
    private val drawerSearchFocus = FocusRequester()
    private val panelFieldFocus = FocusRequester()
    private lateinit var sceneScope: CoroutineScope

    /** Whether the composer holds focus, and how often it has let go of it since [startWriting]. */
    private var composerFocused = false
    private var composerReleases = 0
    private var panelPeak = 0f
    private var drawerPeak = 0f

    private fun show(panel: SidePanelState = SidePanelState(SidePanelValue.Closed), drawer: CursorDrawerState = CursorDrawerState(DrawerValue.Closed)) {
        compose.setContent {
            LaunchedEffect(panel) { snapshotFlow { panel.fraction }.collect { panelPeak = maxOf(panelPeak, it) } }
            LaunchedEffect(drawer) { snapshotFlow { drawer.fraction }.collect { drawerPeak = maxOf(drawerPeak, it) } }
            val scope = rememberCoroutineScope()
            sceneScope = scope
            CursorTheme(mode = ThemeMode.Dark) {
                CursorDrawer(
                    state = drawer,
                    drawerWidth = DrawerWidth,
                    drawerContent = {
                        Column(Modifier.fillMaxSize().testTag("drawer-body")) {
                            BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().padding(16.dp).focusRequester(drawerSearchFocus).testTag("drawer-search"))
                        }
                    },
                ) {
                    SidePanelHost(
                        state = panel,
                        panelWidth = PanelWidth,
                        panelContent = {
                            Column(Modifier.fillMaxSize().testTag("panel-body")) {
                                BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().padding(16.dp).focusRequester(panelFieldFocus).testTag("panel-field"))
                            }
                        },
                    ) {
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                            Row(Modifier.fillMaxWidth()) {
                                FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = { scope.launch { drawer.open() } })
                                Box(Modifier.weight(1f))
                                FlatIconButton(CursorIcons.Sidebar, "Open panel", onClick = { scope.launch { panel.open() } })
                            }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                items(20) { Text("Reply $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp).testTag("reply-$it")) }
                            }
                            Box(
                                Modifier.onFocusChanged {
                                    if (composerFocused && !it.hasFocus) composerReleases++
                                    composerFocused = it.hasFocus
                                },
                            ) {
                                ComposerBox(value = draft, onValueChange = { draft = it }, placeholder = "Follow up", onSend = {}, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val composer: SemanticsNodeInteraction
        get() = compose.onNode(hasSetTextAction() and !hasAnyAncestor(hasTestTag("drawer-body") or hasTestTag("panel-body")))

    private fun fieldText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun softInputVisible(): Boolean =
        shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    /** The composer holding a half-written follow-up, with the keyboard up for it; with [type] false, the draft is already there. */
    private fun startWriting(type: Boolean = true) {
        composer.performClick()
        if (type) composer.performTextInput(Draft)
        composer.assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }
        composerReleases = 0
    }

    /** The composer let go, the keyboard with it, and the follow-up still as it was written. */
    private fun assertComposerReleased() {
        compose.waitForIdle()
        composer.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
        assertThat(fieldText(composer)).isEqualTo(Draft)
        compose.runOnIdle { assertThat(draft).isEqualTo(Draft) }
    }

    /** The composer still focused and the keyboard still up, having never gone down along the way. */
    private fun assertComposerKept() {
        compose.waitForIdle()
        composer.assertIsFocused()
        assertThat(composerReleases).isEqualTo(0)
        assertThat(softInputVisible()).isTrue()
        assertThat(fieldText(composer)).isEqualTo(Draft)
    }

    private fun px(dp: Dp): Int = with(compose.density) { dp.roundToPx() }

    private val windowWidth: Float get() = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

    private val replyY: Float get() = compose.onNodeWithTag("reply-2").fetchSemanticsNode().boundsInRoot.center.y

    /** A finger down at [from] that travels [dx] at [pxPerSecond], one move a frame. */
    private fun TouchInjectionScope.drag(from: Offset, dx: Float, pxPerSecond: Float) {
        val frames = (abs(dx) / (pxPerSecond * FrameMillis / 1000f)).roundToInt().coerceAtLeast(1)
        down(from)
        repeat(frames) { moveBy(Offset(dx / frames, 0f), delayMillis = FrameMillis) }
    }

    /** [drag], and the finger stays down. */
    private fun dragWithoutLifting(from: Offset, dx: Float, pxPerSecond: Float) {
        compose.onRoot().performTouchInput { drag(from, dx, pxPerSecond) }
        compose.waitForIdle()
    }

    private fun release(finish: TouchInjectionScope.() -> Unit = { up() }) {
        compose.onRoot().performTouchInput { finish() }
        compose.waitForIdle()
    }

    /** [drag], then [finish]: a lift, unless told otherwise. */
    private fun swipe(from: Offset, dx: Float, pxPerSecond: Float, finish: TouchInjectionScope.() -> Unit = { up() }) {
        compose.onRoot().performTouchInput {
            drag(from, dx, pxPerSecond)
            finish()
        }
        compose.waitForIdle()
    }

    private fun assertRestsAt(panel: SidePanelState, value: SidePanelValue) {
        compose.waitForIdle()
        assertThat(panel.targetValue).isEqualTo(value)
        assertThat(panel.fraction).isWithin(0.001f).of(if (value == SidePanelValue.Open) 1f else 0f)
    }

    private fun assertRestsAt(drawer: CursorDrawerState, value: DrawerValue) {
        compose.waitForIdle()
        assertThat(drawer.targetValue).isEqualTo(value)
        assertThat(drawer.fraction).isWithin(0.001f).of(if (value == DrawerValue.Open) 1f else 0f)
    }

    // -- the side panel -----------------------------------------------------------------------------------------------

    @Test
    fun `a swipe that opens the panel keeps the keyboard up while the finger drags and puts it away once the release commits the open`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        startWriting()

        // Past half-way and slow, so it is the release that decides: the finger is still down, nothing is committed.
        dragWithoutLifting(Offset(windowWidth * 0.8f, replyY), dx = -px(PanelWidth) * 0.6f, pxPerSecond = SlowPxPerSecond)
        assertThat(panel.fraction).isGreaterThan(0.5f)
        assertThat(panel.targetValue).isEqualTo(SidePanelValue.Closed)
        assertComposerKept()

        release()
        assertRestsAt(panel, SidePanelValue.Open)
        assertComposerReleased()
    }

    @Test
    fun `a flick that opens the panel puts the keyboard away`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        startWriting()
        swipe(Offset(windowWidth * 0.8f, replyY), dx = -px(PanelWidth) * 0.3f, pxPerSecond = FlickPxPerSecond)
        assertRestsAt(panel, SidePanelValue.Open)
        assertComposerReleased()
    }

    @Test
    fun `a panel swipe that settles back shut leaves the composer and the keyboard be`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        startWriting()

        // Let go short of half-way, slowly.
        swipe(Offset(windowWidth * 0.8f, replyY), dx = -px(PanelWidth) * 0.3f, pxPerSecond = SlowPxPerSecond)
        assertThat(panelPeak).isGreaterThan(0.2f)
        assertRestsAt(panel, SidePanelValue.Closed)
        assertComposerKept()

        // Taken away mid-way, as the system cancels a gesture it has claimed: a fling's speed, and still no open.
        swipe(Offset(windowWidth * 0.7f, replyY), dx = -px(PanelWidth) * 0.3f, pxPerSecond = FlickPxPerSecond) { cancel() }
        assertRestsAt(panel, SidePanelValue.Closed)
        assertComposerKept()
    }

    @Test
    fun `the system's back swipe from the right edge neither opens the panel nor touches the keyboard`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        startWriting()
        val strip = px(BackEdgeMinWidth)
        swipe(Offset(windowWidth - strip / 2f, replyY), dx = -px(PanelWidth) * 0.3f, pxPerSecond = FlickPxPerSecond) { cancel() }
        assertRestsAt(panel, SidePanelValue.Closed)
        assertThat(panelPeak).isEqualTo(0f)
        assertComposerKept()
    }

    @Test
    fun `the panel's button puts the keyboard away as the sheet starts to slide in`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        startWriting()

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Open panel").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            assertThat(panel.fraction).isLessThan(1f)
            composer.assertIsNotFocused()
            assertThat(softInputVisible()).isFalse()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertRestsAt(panel, SidePanelValue.Open)
        assertComposerReleased()
    }

    @Test
    fun `a field in the panel keeps focus and the keyboard when the panel, caught on its way shut, is sent back open`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        show(panel)
        compose.onNodeWithContentDescription("Open panel").performClick()
        assertRestsAt(panel, SidePanelValue.Open)
        compose.onNodeWithTag("panel-field").performClick()
        compose.onNodeWithTag("panel-field").assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { sceneScope.launch { panel.close() } }
            repeat(6) { compose.mainClock.advanceTimeByFrame() }
            assertThat(panel.targetValue).isEqualTo(SidePanelValue.Closed)
            assertThat(panel.fraction).isGreaterThan(0f)
            compose.runOnIdle { sceneScope.launch { panel.open() } }
            compose.mainClock.advanceTimeByFrame()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertRestsAt(panel, SidePanelValue.Open)
        compose.onNodeWithTag("panel-field").assertIsFocused()
        assertThat(softInputVisible()).isTrue()
    }

    // -- the sidebar drawer -------------------------------------------------------------------------------------------

    @Test
    fun `a swipe that opens the drawer keeps the keyboard up while the finger drags and puts it away once the release commits the open`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        startWriting()

        dragWithoutLifting(Offset(windowWidth * 0.2f, replyY), dx = px(DrawerWidth) * 0.6f, pxPerSecond = SlowPxPerSecond)
        assertThat(drawer.fraction).isGreaterThan(0.5f)
        assertThat(drawer.targetValue).isEqualTo(DrawerValue.Closed)
        assertComposerKept()

        release()
        assertRestsAt(drawer, DrawerValue.Open)
        assertComposerReleased()
    }

    @Test
    fun `a drawer swipe that settles back shut, or the back swipe from the left edge, leaves the composer and the keyboard be`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        startWriting()

        swipe(Offset(windowWidth * 0.2f, replyY), dx = px(DrawerWidth) * 0.3f, pxPerSecond = SlowPxPerSecond)
        assertThat(drawerPeak).isGreaterThan(0.2f)
        assertRestsAt(drawer, DrawerValue.Closed)
        assertComposerKept()

        swipe(Offset(windowWidth * 0.2f, replyY), dx = px(DrawerWidth) * 0.3f, pxPerSecond = FlickPxPerSecond) { cancel() }
        assertRestsAt(drawer, DrawerValue.Closed)
        assertComposerKept()

        drawerPeak = 0f
        val strip = px(BackEdgeMinWidth)
        swipe(Offset(strip / 2f, replyY), dx = windowWidth * 0.3f, pxPerSecond = FlickPxPerSecond) { cancel() }
        assertRestsAt(drawer, DrawerValue.Closed)
        assertThat(drawerPeak).isEqualTo(0f)
        assertComposerKept()
    }

    @Test
    fun `the drawer's button puts the keyboard away`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        startWriting()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        assertRestsAt(drawer, DrawerValue.Open)
        assertComposerReleased()
    }

    @Test
    fun `the drawer's own search, holding focus as the drawer opens, keeps it and the keyboard`() {
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(drawer = drawer)
        compose.runOnIdle { drawerSearchFocus.requestFocus() }
        compose.onNodeWithTag("drawer-search").assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }

        swipe(Offset(windowWidth * 0.2f, replyY), dx = px(DrawerWidth) * 0.3f, pxPerSecond = FlickPxPerSecond)
        assertRestsAt(drawer, DrawerValue.Open)
        compose.onNodeWithTag("drawer-search").assertIsFocused()
        assertThat(softInputVisible()).isTrue()
    }

    @Test
    fun `the draft rides out both sheets opening and closing, and the composer takes the keyboard back on a tap`() {
        val panel = SidePanelState(SidePanelValue.Closed)
        val drawer = CursorDrawerState(DrawerValue.Closed)
        show(panel, drawer)
        startWriting()

        compose.onNodeWithContentDescription("Open panel").performClick()
        assertRestsAt(panel, SidePanelValue.Open)
        assertComposerReleased()
        compose.onNodeWithContentDescription("Dismiss panel").performSemanticsAction(SemanticsActions.OnClick)
        assertRestsAt(panel, SidePanelValue.Closed)

        startWriting(type = false)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        assertRestsAt(drawer, DrawerValue.Open)
        assertComposerReleased()
        // The drawer's search takes the keyboard as usual once the composer has let it go.
        compose.onNodeWithTag("drawer-search").performClick()
        compose.onNodeWithTag("drawer-search").assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }
        compose.onNodeWithContentDescription("Close navigation menu").performSemanticsAction(SemanticsActions.OnClick)
        assertRestsAt(drawer, DrawerValue.Closed)

        startWriting(type = false)
        assertThat(fieldText(composer)).isEqualTo(Draft)
    }

    private companion object {
        const val FrameMillis = 16L
        const val Draft = "Half a thought about the"
        /** Well under the 400 dp/s fling threshold (1 050 px/s at 420 dpi): the release settles by where the sheet is. */
        const val SlowPxPerSecond = 500f
        /** Well over it. */
        const val FlickPxPerSecond = 3_000f
        val PanelWidth = 360.dp
        val DrawerWidth = 300.dp
    }
}
