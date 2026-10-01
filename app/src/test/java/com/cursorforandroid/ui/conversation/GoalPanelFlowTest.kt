package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RetryOnLeakedExceptions
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The goal panel through the whole app on the demo's goal chat: opened with the keyboard down and up, scrolled to the
 * end of its long objective inside the page and back, closed; the composer expanded over it, which closes it; on a
 * phone and on a wide window.
 *
 * With `GOAL_DEMO_DIR` set, every other frame is written there as a PNG for the demo video. `GOAL_DEMO_UNCHECKED` runs
 * the same script without its assertions, to record a build where the panel still runs off the page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class GoalPanelFlowTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)

    private lateinit var graph: AppGraph
    private val demoDir: File? = System.getenv("GOAL_DEMO_DIR")?.let(::File)
    private val checked = System.getenv("GOAL_DEMO_UNCHECKED") == null
    private var scene = ""
    private var frame = 0
    private var imePx = 0

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            // The demo has a Project, so its New Chat page would open on Projects; the goal chat is opened from the recent cards.
            graph.prefs.setNewChatHome(NewChatHome.RECENT)
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {})
            }
        }
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        runBlocking { graph.drafts.clear() }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun dp(px: Float) = px / density

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun rootBottom() = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    private fun check(message: String, ok: Boolean) {
        if (checked) assertWithMessage(message).that(ok).isTrue()
    }

    private fun openGoalChat() {
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(GOAL_CHAT, substring = true)) }.isSuccess
        }
        compose.onAllNodesWithText(GOAL_CHAT).onFirst().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(STRIP)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun keyboard(heightDp: Int) {
        imePx = (heightDp * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (16 * density).toInt()))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeView(), insets) }
    }

    private fun capture() {
        val dir = demoDir ?: return
        dir.mkdirs()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        if (imePx > 0) paintKeyboard(bitmap, imePx, density)
        File(dir, "%s_%04d.png".format(scene, frame++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun frames(count: Int) {
        repeat(if (demoDir != null) count else 1) {
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
    }

    private fun play(count: Int) {
        if (demoDir != null) frames(count) else compose.mainClock.advanceTimeBy(count * 32L)
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.onNode(hasTestTag(STRIP)).performTouchInput(block)
        compose.mainClock.autoAdvance = false
    }

    /** A finger drawn [dyDp] along the panel in [steps] moves, held still, then lifted. */
    private fun drag(dyDp: Float, steps: Int) {
        val step = dyDp * density / steps
        touch { down(Offset(centerX, if (dyDp < 0) bottom - height * 0.2f else top + height * 0.2f)) }
        repeat(steps) {
            touch { moveBy(Offset(0f, step)) }
            frames(1)
        }
        frames(4)
        touch { up() }
    }

    private fun tapStrip() {
        touch { click(Offset(centerX, top + 20 * density)) }
    }

    private fun detailsVisible() = compose.onAllNodes(hasTestTag("goal-details"), useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.let { it.boundsInRoot.height >= it.size.height - 1 } == true

    /** Opened, read to the end and back inside its bounds, then closed. */
    private fun readGoal(label: String, header: Rect) {
        val closed = bounds(hasTestTag(STRIP))
        tapStrip()
        play(18)
        frames(12)
        val open = bounds(hasTestTag(STRIP))
        val composer = bounds(hasTestTag(COMPOSER))
        val floor = rootBottom() - imePx
        check("$label: the panel opened taller than it was", open.height > closed.height * 2)
        check("$label: its top stays under the header (${dp(open.top - header.bottom)}dp)", open.top >= header.bottom - 1)
        check("$label: it ends above the composer", open.bottom <= composer.top + 1)
        check("$label: the composer stays whole above the keyboard", composer.bottom <= floor + 1)
        repeat(8) { drag(dyDp = -140f, steps = 8) }
        frames(12)
        check("$label: scrolled to the objective's last detail", detailsVisible())
        repeat(4) { drag(dyDp = 200f, steps = 8) }
        frames(8)
        tapStrip()
        play(18)
        frames(10)
        check("$label: closed back to the strip", kotlin.math.abs(bounds(hasTestTag(STRIP)).height - closed.height) <= 2f)
    }

    private fun flow(label: String, keyboardDp: Int) {
        scene = label
        openGoalChat()
        compose.mainClock.autoAdvance = false
        frames(12)
        val header = bounds(hasTestTag("transcript")).let { Rect(it.left, it.top - 1f, it.right, it.top) }

        readGoal("$label keyboard down", header)

        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER))).performClick()
        keyboard(keyboardDp)
        play(10)
        readGoal("$label keyboard up", header)

        // Open again, then the composer expanded: the two take turns at the page.
        tapStrip()
        play(18)
        frames(8)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER))).performTextInput(PROMPT)
        play(12)
        compose.onAllNodes(hasTestTag("composer-expand") and hasAnyAncestor(hasTestTag(COMPOSER)), useUnmergedTree = true)
            .fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
                compose.onAllNodes(hasTestTag("composer-expand") and hasAnyAncestor(hasTestTag(COMPOSER)), useUnmergedTree = true).onFirst().performClick()
                play(18)
                frames(10)
                check("$label: expanding the composer closed the goal", bounds(hasTestTag(STRIP)).height < 60 * density)
                check("$label: and the composer stays above the keyboard", bounds(hasTestTag(COMPOSER)).bottom <= rootBottom() - imePx + 1)
            } ?: check("$label: the long prompt offered the expand button", false)

        keyboard(0)
        imePx = 0
        play(10)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `phone - the goal panel reads to the end inside the page with the keyboard down and up`() = flow("phone", keyboardDp = 290)

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - the goal panel reads to the end inside the page with the keyboard down and up`() = flow("wide", keyboardDp = 260)

    private companion object {
        const val GOAL_CHAT = "Hyper-realistic human limbs"
        const val STRIP = "goal-strip"
        const val COMPOSER = "follow-up-composer"
        val PROMPT = (1..14).joinToString("\n") { "$it. Keep the rig's contact deformation stable at every pose in the grip set." }
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
