package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RetryOnLeakedExceptions
import com.google.common.truth.Truth.assertThat
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
 * The expand button through the whole app on the demo, with the keyboard up: a long prompt typed into a chat's composer
 * until the button slides in beside "+", the composer expanded to run from under the header to the keyboard's edge
 * with the transcript squeezed out behind it, edited there, collapsed, expanded again and sent — which brings it back
 * down; and the same on the New Chat page, where it grows up into the room the page centred it in. On a phone and on a
 * wide window with the sidebar beside the chat and its panel pinned.
 *
 * With `EXPAND_DEMO_DIR` set, every other frame is written there as a PNG — the keyboard's area painted as a keyboard,
 * which Robolectric does not draw — for the demo video.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComposerExpandFlowTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)

    private lateinit var graph: AppGraph
    private val motion = SendMotion(animatorsEnabled = { true })
    private val demoDir: File? = System.getenv("EXPAND_DEMO_DIR")?.let(::File)
    private var scene = ""
    private var frame = 0
    private var imePx = 0

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SendMotionHost(motion) {
                    CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {})
                }
            }
        }
        waitForText(NewChatHomeCopy.PLACEHOLDER, 30_000)
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        runBlocking { graph.drafts.clear() }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) = compose.waitUntil(timeoutMillis) { onScreen(text) }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun dp(px: Float) = px / density

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun rootHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    private fun openIdleChat() {
        compose.waitUntil(30_000) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(IDLE_CHAT, substring = true)) }.isSuccess
        }
        compose.onAllNodesWithText(IDLE_CHAT).onFirst().performClick()
        waitForText(CHAT_PROMPT, 30_000)
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

    /** The keyboard up, [heightDp] tall over a 16dp navigation bar, as a device's window reports it. */
    private fun keyboardUp(heightDp: Int) {
        imePx = (heightDp * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (16 * density).toInt()))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeView(), insets) }
        compose.waitForIdle()
    }

    private fun capture() {
        val dir = demoDir ?: return
        dir.mkdirs()
        // Drawn here rather than through captureToImage, which waits for a redraw the held clock never lets happen.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        if (imePx > 0) paintKeyboard(bitmap, imePx, density)
        File(dir, "%s_%04d.png".format(scene, frame++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [count] frames of the video, two of the app's apart (30 fps), the clock stepped between them. */
    private fun frames(count: Int) {
        repeat(if (demoDir != null) count else 1) {
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
    }

    /** Lets whatever [count] frames' worth of animation there is finish, recording it when a demo is asked for. */
    private fun play(count: Int) {
        if (demoDir != null) frames(count) else compose.mainClock.advanceTimeBy(count * 32L)
    }

    private fun inComposer(tag: String) = hasAnyAncestor(hasTestTag(tag))
    private fun field(tag: String) = compose.onNode(hasSetTextAction() and inComposer(tag))
    private fun expandButton(tag: String) = hasTestTag("composer-expand") and inComposer(tag)
    private fun offered(tag: String) = compose.onAllNodes(expandButton(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun named(tag: String, description: String) =
        compose.onAllNodes(hasContentDescription(description) and inComposer(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tapExpand(tag: String) {
        compose.onAllNodes(expandButton(tag), useUnmergedTree = true).onFirst().performClick()
    }

    private fun editable(tag: String) = field(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Types the prompt a line at a time, a few frames after each, until the button has slid in and [extra] lines past it. */
    private fun typeUntilOffered(tag: String, extra: Int): Int {
        var typed = 0
        var after = -1
        while (typed < PROMPT.size && after < extra) {
            field(tag).performTextInput((if (typed == 0) "" else "\n") + PROMPT[typed])
            typed++
            frames(4)
            if (after >= 0 || offered(tag)) after++
        }
        assertWithMessage("the button slid in once the prompt scrolled inside the composer").that(offered(tag)).isTrue()
        return typed
    }

    /** The chat: typed until offered, expanded, edited, collapsed, expanded again and sent. */
    private fun chatFlow(label: String, keyboardDp: Int, pinPanel: Boolean) {
        scene = "${label}_chat"
        openIdleChat()
        if (pinPanel) {
            compose.onAllNodes(hasContentDescription("Open panel")).onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Hide panel")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        }
        field(COMPOSER).performClick()
        keyboardUp(keyboardDp)
        compose.mainClock.autoAdvance = false
        frames(12)
        typeUntilOffered(COMPOSER, extra = 3)
        val collapsed = bounds(hasTestTag(COMPOSER))
        val transcript = bounds(hasTestTag("transcript"))
        frames(10)

        tapExpand(COMPOSER)
        play(14)
        frames(10)
        val expanded = bounds(hasTestTag(COMPOSER))
        val keyboardTop = rootHeight() - imePx
        assertWithMessage("$label: expanded, the composer still sits on the keyboard").that(expanded.bottom).isWithin(1f).of(collapsed.bottom)
        assertWithMessage("$label: above the keyboard's edge").that(expanded.bottom).isAtMost(keyboardTop.toFloat())
        assertWithMessage("$label: up to just under the header").that(dp(expanded.top - transcript.top)).isAtMost(12f)
        assertWithMessage("$label: far taller than collapsed").that(expanded.height).isGreaterThan(collapsed.height * 1.3f)
        assertWithMessage("$label: the transcript squeezed out behind it").that(dp(bounds(hasTestTag("transcript")).height)).isAtMost(12f)
        assertThat(named(COMPOSER, "Collapse composer")).isTrue()

        // Edited where it was left: a line put on the end.
        field(COMPOSER).performTextInput("\n" + EDIT)
        frames(16)
        tapExpand(COMPOSER)
        play(14)
        frames(10)
        assertWithMessage("$label: collapsed back to where it was").that(bounds(hasTestTag(COMPOSER)).height).isWithin(2f).of(collapsed.height)
        assertThat(editable(COMPOSER)).endsWith(EDIT)

        tapExpand(COMPOSER)
        play(14)
        frames(8)
        compose.onNode(hasContentDescription("Send") and inComposer(COMPOSER), useUnmergedTree = true).performClick()
        play(40)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.waitUntil(10_000) { !offered(COMPOSER) }
        assertWithMessage("$label: the send took the composer back down").that(bounds(hasTestTag("transcript")).height).isGreaterThan(transcript.height / 2)
        waitForText(EDIT)
    }

    /** New Chat: typed until offered, expanded up into the page, collapsed back into its place. */
    private fun newChatFlow(label: String, keyboardDp: Int) {
        scene = "${label}_new_chat"
        val tag = NewChatHomeTags.COMPOSER
        field(tag).performClick()
        keyboardUp(keyboardDp)
        compose.mainClock.autoAdvance = false
        frames(12)
        typeUntilOffered(tag, extra = 2)
        val collapsed = bounds(hasTestTag(tag))
        frames(8)
        tapExpand(tag)
        play(16)
        frames(10)
        val expanded = bounds(hasTestTag(tag))
        val keyboardTop = rootHeight() - imePx
        assertWithMessage("$label: above the keyboard's edge").that(expanded.bottom).isAtMost(keyboardTop.toFloat())
        assertWithMessage("$label: running down to near it").that(dp(keyboardTop - expanded.bottom)).isAtMost(40f)
        assertWithMessage("$label: grown up into the page").that(expanded.top).isAtMost(collapsed.top)
        assertWithMessage("$label: nearly the page's height").that(expanded.height).isGreaterThan(keyboardTop * 0.6f)
        field(tag).performTextInput("\n" + EDIT)
        frames(12)
        tapExpand(tag)
        play(16)
        frames(12)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(named(tag, "Expand composer")).isTrue()
        assertThat(editable(tag)).endsWith(EDIT)
    }

    @Test
    fun `phone - a chat's composer expands over the transcript to the keyboard and back, and a send collapses it`() = chatFlow("phone", keyboardDp = 290, pinPanel = false)

    @Test
    fun `phone - the New Chat composer expands up the page to the keyboard and back`() = newChatFlow("phone", keyboardDp = 290)

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - a chat's composer expands beside the sidebar and the pinned panel`() = chatFlow("wide", keyboardDp = 260, pinPanel = true)

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `wide - the New Chat composer expands up the page to the keyboard and back`() = newChatFlow("wide", keyboardDp = 260)

    private companion object {
        const val IDLE_CHAT = "Cli exploration"
        const val CHAT_PROMPT = "Explore how the Cursor CLI resumes cloud agents"
        const val COMPOSER = "follow-up-composer"
        const val EDIT = "Keep the old flag working for a release."
        val PROMPT = listOf(
            "Make resume survive a dropped connection.",
            "1. Keep the run id on disk the moment the run starts.",
            "2. On reconnect, read the last event id we rendered.",
            "3. Ask the stream to replay from that event, not from zero.",
            "4. Dedupe by event id so nothing renders twice.",
            "5. If the run has ended meanwhile, fetch the final state instead.",
            "6. Show a quiet \"Reconnecting…\" line while it happens.",
            "7. Give up after three tries and say why, with a Retry.",
            "8. Cover it with the fault server at 300-900 ms.",
            "9. Include a 429 in the script and back off on it.",
            "10. No new dependencies.",
            "11. Keep the public API of RunStream unchanged.",
            "12. Add a line to the changelog.",
            "13. Open a draft PR when done.",
            "14. Put the test output in the PR body.",
            "15. Ping me with anything you are unsure about.",
        )
    }
}

/** A stand-in for the on-screen keyboard over the inset the app was given, which Robolectric leaves blank. */
private fun paintKeyboard(bitmap: Bitmap, heightPx: Int, density: Float) {
    val canvas = Canvas(bitmap)
    val top = (bitmap.height - heightPx).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF202124.toInt()
    canvas.drawRect(0f, top, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
    val pad = 6 * density
    val rows = listOf(10, 9, 9, 5)
    val rowHeight = (heightPx - 16 * density - pad) / (rows.size + 0.4f)
    paint.color = 0xFF3C4043.toInt()
    val maxKeys = rows.first()
    val keyWidth = (bitmap.width - pad) / maxKeys
    rows.forEachIndexed { index, keys ->
        val y = top + pad + index * rowHeight + 0.4f * rowHeight
        val widths = if (index == rows.lastIndex) listOf(1.5f, 1f, 4f, 1f, 1.5f) else List(keys) { 1f }
        var x = (bitmap.width - widths.sum() * keyWidth) / 2
        for (w in widths) {
            canvas.drawRoundRect(RectF(x + pad / 2, y, x + w * keyWidth - pad / 2, y + rowHeight - pad), 6 * density, 6 * density, paint)
            x += w * keyWidth
        }
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
