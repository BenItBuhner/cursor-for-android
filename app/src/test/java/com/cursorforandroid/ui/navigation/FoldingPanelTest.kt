package com.cursorforandroid.ui.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.HapticLog
import com.cursorforandroid.ui.components.ShadowHapticLog
import com.cursorforandroid.ui.components.constant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Pixel Fold's inner screen held open, its cover screen, and the inner screen turned upright; and a small phone, short
 * enough that the Project's notes scroll on it. At mdpi, so that a pixel is a dp.
 */
private const val INNER = "w841dp-h701dp-land-night-mdpi"
private const val COVER = "w411dp-h797dp-port-night-mdpi"
private const val INNER_TURNED = "w701dp-h841dp-port-night-mdpi"
private const val SMALL_PHONE = "w360dp-h640dp-port-night-mdpi"

/**
 * The conversation panel in the running app as the window changes under it without the activity being made again, as
 * a foldable folds, unfolds and turns: the panel pinned beside the chat is put away folded and stands again unfolded,
 * as wide as it was, on the tab it was on and scrolled as far; each window size class keeps its own open or shut; and a
 * hand's open or shut is felt once as it lands on whichever screen, while the fold itself is not felt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = INNER)
class FoldingPanelTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private var activity: ActivityController<MainActivity>? = null

    /** Torn down with the test, so that its composition is not left collecting the app graph on the shared main looper. */
    @After
    fun destroyActivity() {
        activity?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The app on the demo, then the Project chat opened by a link, as a notification opens it. The link comes to the
     * running app: one the launch carries is taken while the first composition is still being applied, which under the
     * test's dispatcher leaves the shell on Home with the chat on its stack.
     */
    private fun launchOnProject() {
        runBlocking { app.appGraph.session.enterDemo() }
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { activity = it }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        launched.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://cursor.com/agents/${DemoData.PROJECT_ID}")).setClass(app, MainActivity::class.java))
        compose.waitUntil(30_000) { exists(hasTestTag("chat-header")) && onScreen(CHAT_PLACEHOLDER) }
        compose.waitForIdle()
    }

    /**
     * The window made [qualifiers] under the running activity, which takes a fold, an unfold or a turn in place;
     * [firstFrame] looks at the first frame drawn at it, before anything has had time to move, and the rest then settles.
     */
    private fun window(qualifiers: String, firstFrame: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        RuntimeEnvironment.setQualifiers(qualifiers)
        activity!!.configurationChange()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
        firstFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun onScreen(text: String) = exists(hasText(text, substring = true))

    private fun described(description: String) = exists(hasContentDescription(description))

    private fun displayed(matcher: SemanticsMatcher) = runCatching { compose.onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess

    private fun panelShown() = displayed(hasTestTag(PANEL))

    private fun pinned() = panelShown() && described(HIDE_PANEL)

    private fun panelBounds(): Rect = compose.onNode(hasTestTag(PANEL)).fetchSemanticsNode().boundsInRoot

    private fun assertStandsAt(bounds: Rect) {
        assertThat(pinned()).isTrue()
        assertThat(panelBounds().left).isWithin(0.5f).of(bounds.left)
        assertThat(panelBounds().width).isWithin(0.5f).of(bounds.width)
    }

    /** The Project's notes read and laid out on the panel's Project tab, long enough to scroll. */
    private fun waitForNotes() {
        compose.waitUntil(30_000) { exists(hasTestTag(NOTES_BODY)) }
        compose.waitForIdle()
    }

    private fun notesScrolled(): Float = compose.onNode(hasTestTag(NOTES)).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrollNotes(by: Float) {
        compose.onNode(hasTestTag(NOTES)).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        compose.waitForIdle()
    }

    /** A finger down on the boundary, across by [dx] and up: the edge follows it the whole way. */
    private fun drag(edge: String, dx: Float) {
        compose.onNodeWithContentDescription(edge).performTouchInput {
            down(center)
            moveBy(Offset(dx, 0f))
            up()
        }
        compose.waitForIdle()
    }

    /** A quick swipe toward the start across the chat's transcript, as a thumb opens the panel over it. */
    private fun flickOpen() {
        compose.onNode(hasTestTag(TRANSCRIPT)).performTouchInput {
            down(Offset(width * 0.85f, centerY))
            repeat(4) { moveBy(Offset(-25f, 0f), delayMillis = 16) }
            up()
        }
        compose.waitForIdle()
    }

    private val landed: Int get() = Haptic.GestureEnd.constant()

    private val stop: Int get() = Haptic.SlotTick.constant()

    @Test
    fun `folded and unfolded under it, the pinned panel stands again as wide as it was, on its tab and scrolled as far, and turned it keeps each size class's own`() {
        launchOnProject()
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { pinned() }
        waitForNotes()
        drag(RESIZE_PANEL, -60f)
        val stood = panelBounds()
        assertThat(stood.width).isWithin(0.5f).of(841f / 2 + 60f)
        scrollNotes(60f)
        val scrolled = notesScrolled()
        assertThat(scrolled).isGreaterThan(0f)

        // Folded, the cover has no room beside the chat: the panel is put away, not left over the chat as a sheet.
        window(COVER) { assertThat(panelShown()).isFalse() }
        assertThat(panelShown()).isFalse()
        assertThat(described(OPEN_PANEL)).isTrue()

        window(INNER) { assertStandsAt(stood) }
        assertStandsAt(stood)
        assertThat(notesScrolled()).isEqualTo(scrolled)

        // Turned upright, the inner screen is a Medium window, where the panel was never opened.
        window(INNER_TURNED) { assertThat(panelShown()).isFalse() }
        assertThat(panelShown()).isFalse()
        assertThat(described(OPEN_PANEL)).isTrue()

        // Turned back, the rail the upright window had room for is gone in the same frame, not sliding out while the
        // panel widens after it.
        window(INNER) { assertStandsAt(stood) }
        assertStandsAt(stood)
        assertThat(notesScrolled()).isEqualTo(scrolled)
    }

    @Test
    @Config(qualifiers = COVER, shadows = [ShadowHapticLog::class])
    fun `a panel swiped open on the cover is felt once as it lands, not again as the unfold pins it or a fold puts it away, and once each as a button or its edge moves it there`() {
        launchOnProject()
        HapticLog.clear()

        flickOpen()
        compose.waitUntil(10_000) { panelShown() && described(DISMISS_PANEL) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed)

        window(INNER)
        assertThat(pinned()).isTrue()
        assertThat(HapticLog.played).containsExactly(landed)

        compose.onNodeWithContentDescription(HIDE_PANEL).performClick()
        compose.waitUntil(10_000) { !panelShown() && described(OPEN_PANEL) }
        compose.waitForIdle()
        assertThat(HapticLog.played).containsExactly(landed, landed)

        // Opened again and dragged out past its widest, which the chat's least width sets here, then folded away.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { pinned() }
        compose.waitForIdle()
        drag(RESIZE_PANEL, -300f)
        assertThat(panelBounds().width).isWithin(1f).of(841f - 320f)
        window(COVER)
        assertThat(panelShown()).isFalse()
        assertThat(HapticLog.played).containsExactly(landed, landed, landed, stop)
    }

    /** The chat's column: it starts where the rail ends, at the window's edge with the rail away. */
    private fun chatBounds(): Rect = compose.onNode(hasTestTag(TRANSCRIPT)).fetchSemanticsNode().boundsInRoot

    @Test
    @Config(qualifiers = COVER)
    fun `a sheet open on the cover stands pinned at its width from the first frame of the unfold, the rail away and the notes where they were`() {
        launchOnProject()
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { panelShown() && described(DISMISS_PANEL) }
        waitForNotes()
        scrollNotes(200f)
        val scrolled = notesScrolled()
        assertThat(scrolled).isGreaterThan(0f)

        // The chat at its least beside the panel at half the window leaves the rail no room: it is away as the window
        // first stands, not beside the chat a frame and then sliding off with the panel widening after it, and the chat
        // and the panel share the window half and half.
        val unfolded = {
            assertThat(pinned()).isTrue()
            assertThat(panelBounds().width).isWithin(0.5f).of(841f / 2)
            assertThat(chatBounds().left).isWithin(0.5f).of(0f)
            assertThat(chatBounds().width).isWithin(0.5f).of(841f / 2)
        }
        window(INNER) { unfolded() }
        unfolded()
        assertThat(notesScrolled()).isEqualTo(scrolled)
    }

    private fun composerFocused() = exists(hasSetTextAction() and isFocused() and hasAnyAncestor(hasTestTag(COMPOSER)))

    @Test
    @Config(qualifiers = COVER)
    fun `a draft being typed keeps the caret through a fold, an unfold and a turn, and a composer a sheet took it from is not given it back`() {
        launchOnProject()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER))).performClick()
        compose.waitUntil(10_000) { composerFocused() }
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput(DRAFT)
        compose.waitForIdle()

        window(INNER) { assertThat(composerFocused()).isTrue() }
        assertThat(composerFocused()).isTrue()
        window(INNER_TURNED) { assertThat(composerFocused()).isTrue() }
        window(COVER) { assertThat(composerFocused()).isTrue() }
        assertThat(composerFocused()).isTrue()
        assertThat(onScreen(DRAFT)).isTrue()

        // The sheet covers the composer, so it takes the caret as it opens; unfolded, it stands pinned and leaves it gone.
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { panelShown() && described(DISMISS_PANEL) }
        compose.waitForIdle()
        assertThat(composerFocused()).isFalse()
        window(INNER)
        assertThat(pinned()).isTrue()
        assertThat(composerFocused()).isFalse()
        assertThat(onScreen(DRAFT)).isTrue()
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun `on a phone a tab comes back scrolled as it was left when the panel is shut and opened again`() {
        launchOnProject()
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { panelShown() && described(DISMISS_PANEL) }
        waitForNotes()
        scrollNotes(300f)
        val scrolled = notesScrolled()
        assertThat(scrolled).isGreaterThan(0f)

        compose.onNode(hasTestTag(PANEL_CLOSE)).performClick()
        compose.waitUntil(10_000) { !panelShown() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { panelShown() && described(DISMISS_PANEL) }
        waitForNotes()
        assertThat(notesScrolled()).isEqualTo(scrolled)
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val COMPOSER = "follow-up-composer"
        const val DRAFT = "Bill the upgrade from the next cycle"
        const val PANEL = "conversation-panel"
        const val PANEL_CLOSE = "panel-close"
        const val TRANSCRIPT = "transcript"
        const val NOTES = "project-notes-tab"
        const val NOTES_BODY = "project-notes-body"
        const val OPEN_PANEL = "Open panel"
        const val HIDE_PANEL = "Hide panel"
        const val DISMISS_PANEL = "Dismiss panel"
        const val RESIZE_PANEL = "Resize panel"
    }
}
