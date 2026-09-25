package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentLink
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The status icon a link to an agent opens with (see [AgentLinkIcon]): the dot grid while the list says the agent's run
 * is going, the arrowhead once it is not — switching in place as the run finishes — and the arrowhead too for an agent
 * the list does not hold; the monitor for a link to its desktop. The icon is a slot in the text inside the link, so it
 * stands just before the label on the label's own line, wraps with it, and opens the link when tapped.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinkStatusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val working = "bc-0cbcf799-5a79-59fd-b8b3-b855a5bba8bf"
    private val done = "bc-1708c3c2-1d5c-565b-8e82-aa6e9b671cd1"
    private val unknown = "bc-5e7d0f3a-0b7e-4c2a-9d0e-2f4a6b8c1d3e"
    private val message = "Status so far: [Push AI receptionist to scale]($working) is building, and [Build cold-email fleet at scale]($done) has finished."

    private val running = mutableStateOf(setOf(working))
    private val handed = mutableListOf<AgentLink>()
    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun show(markdown: String = message, width: Dp? = null) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val media = MarkdownMediaContext("bc-coordinator", ViewerFixtures.loader(), onOpenAgentLink = { handed += it })
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalMarkdownMedia provides media,
                    LocalAgentLinkStatuses provides AgentLinkStatuses(running),
                ) {
                    MarkdownText(markdown, modifier = if (width != null) Modifier.width(width) else Modifier)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun paragraph(start: String): SemanticsNodeInteraction = compose.onNode(hasText(start, substring = true), useUnmergedTree = true)

    private fun layout(start: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        paragraph(start).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        return results.single()
    }

    @Test
    fun `a working agent shows the dot grid and a finished one the arrowhead`() {
        show()
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(1)
        assertThat(count(AgentLinkIcon.TAG_IDLE)).isEqualTo(1)
    }

    @Test
    fun `the icon switches to the arrowhead the moment the run finishes, and back when another starts`() {
        show()
        running.value = emptySet()
        compose.waitForIdle()
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(0)
        assertThat(count(AgentLinkIcon.TAG_IDLE)).isEqualTo(2)

        running.value = setOf(done)
        compose.waitForIdle()
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(1)
        assertThat(count(AgentLinkIcon.TAG_IDLE)).isEqualTo(1)
    }

    @Test
    fun `the working icon in the text animates, stepping through the grid's whole loop`() {
        // The test harness cancels infinite animations for good if they start while the clock auto-advances, so the
        // clock is paused before the text is composed, as it is never in the app.
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeByFrame()
        // captureToImage waits for a redraw the paused clock never schedules, so the window is drawn by hand.
        fun drawn(): List<Int> {
            val bounds = compose.onNode(hasTestTag(AgentLinkIcon.TAG_RUNNING), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val window = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            val left = bounds.left.roundToInt()
            val top = bounds.top.roundToInt()
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, left, top, width, height) }.toList()
        }
        val loop = List(8) { step ->
            if (step > 0) compose.mainClock.advanceTimeBy(175)
            drawn()
        }
        assertThat(loop.toSet()).hasSize(8)
        compose.mainClock.advanceTimeBy(175)
        assertThat(drawn()).isEqualTo(loop.first())
    }

    @Test
    fun `an agent the list does not hold shows at rest, and a desktop link shows the monitor`() {
        show("Status so far: [a stranger]($unknown), its [desktop]($working#desktop), and https://cursor.com/agents/$unknown too.")
        assertThat(count(AgentLinkIcon.TAG_IDLE)).isEqualTo(2)
        assertThat(count(AgentLinkIcon.TAG_DESKTOP)).isEqualTo(1)
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(0)
    }

    @Test
    fun `other links and plain prose get no icon`() {
        show("Status so far: the [guide](https://example.com/guide) and `/cursor/stores/bc-bae107cb-2562-40b2-b814-4f8eca874668/notes.md`.")
        assertThat(count(AgentLinkIcon.TAG_IDLE) + count(AgentLinkIcon.TAG_RUNNING) + count(AgentLinkIcon.TAG_DESKTOP)).isEqualTo(0)
        assertThat(layout("Status so far").placeholderRects).isEmpty()
    }

    @Test
    fun `the icon stands just before the label, on the label's line, wherever the label wraps`() {
        // Narrow enough that the second link starts on a later line than the first.
        show(width = 220.dp)
        val layout = layout("Status so far")
        val text = layout.layoutInput.text.text
        val slots = layout.placeholderRects.filterNotNull()
        assertThat(slots).hasSize(2)
        listOf("Push AI", "Build cold-email").forEachIndexed { i, label ->
            val labelStart = text.indexOf(label)
            val slot = slots[i]
            val first = layout.getBoundingBox(labelStart)
            assertThat(layout.getLineForOffset(labelStart - 1)).isEqualTo(layout.getLineForOffset(labelStart))
            assertThat(slot.right).isAtMost(first.left + 0.5f)
            assertThat(slot.top).isAtLeast(layout.getLineTop(layout.getLineForOffset(labelStart)) - 0.5f)
            assertThat(slot.bottom).isAtMost(layout.getLineBottom(layout.getLineForOffset(labelStart)) + 0.5f)
        }
        assertThat(layout.getLineForOffset(text.indexOf("Build cold-email"))).isGreaterThan(layout.getLineForOffset(text.indexOf("Push AI")))
    }

    @Test
    fun `a tap on the icon opens the link like a tap on its label`() {
        show()
        val slot = layout("Status so far").placeholderRects.filterNotNull().first()
        paragraph("Status so far").performTouchInput { click(slot.center) }
        compose.waitForIdle()
        assertThat(handed).containsExactly(AgentLink(working))
        assertThat(opened).isEmpty()
    }

    @Test
    fun `the slot sits inside the link it belongs to, and only where asked for`() {
        val style = TextStyle(fontSize = 14.sp)
        val plain = InlineMarkdown.render(message, style, Color.White, Color.DarkGray, Color.Blue, Color.White, onLinkClick = {})
        assertThat(plain.text).doesNotContain("\uFFFD")

        val withIcons = InlineMarkdown.render(message, style, Color.White, Color.DarkGray, Color.Blue, Color.White, onLinkClick = {}, agentLinkIcons = true)
        val links = withIcons.getLinkAnnotations(0, withIcons.length)
        assertThat(links.map { (it.item as LinkAnnotation.Url).url }).containsExactly(working, done).inOrder()
        links.forEach { link -> assertThat(withIcons.text[link.start]).isEqualTo('\uFFFD') }
        assertThat(AgentLinkIcon.inlineContent(withIcons, Color.Blue).keys)
            .containsExactly(AgentLinkIcon.id(working), AgentLinkIcon.id(done))
    }
}
