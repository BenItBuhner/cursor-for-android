package com.cursorforandroid.ui.conversation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RetryOnLeakedExceptions
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A chat's header in the real screen, over the demo's chats, under a 24dp status bar: the controls in a row 40dp tall
 * whatever the font, every control's 48dp target starting at the status bar's edge and reaching 8dp over the
 * transcript, where a tap still lands on the control; the chat's name on one line beside back (a Project's after its
 * icon), ellipsized short of the right-side buttons and gone with back, no repository line, the name also the row's
 * accessibility label and the panel's header; and Rename still in the menu, whose items keep their order with Reload
 * transcript the one way to read the chat again. The same for an ordinary chat and a Project's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ChatHeaderTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)

    private val density: Float get() = compose.activity.resources.displayMetrics.density

    private var backs = 0

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(agentName: String? = null, agentId: String? = null, back: Boolean = true): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        val id = agentId ?: graph.agents.state.value.agents.first { it.name == agentName }.id
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    if (back) ConversationScreen(graph, id, onBack = { backs++ }) else ConversationScreen(graph, id, onBack = null, onOpenSidebar = {})
                }
            }
        }
        dispatchStatusBar((STATUS_BAR.value * density).toInt())
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("follow-up-composer")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { header().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() != "Chat" }
        return graph
    }

    private fun dispatchStatusBar(px: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), px > 0)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
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

    private fun header() = compose.onNodeWithTag("chat-header").fetchSemanticsNode()

    private fun headerBounds(): DpRect = compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot()

    private fun targetOf(control: String): DpRect = compose.onNodeWithContentDescription(control).getUnclippedBoundsInRoot()

    private fun label(): String? = header().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private val title = hasTestTag("chat-header-title")

    private fun titleBounds(): DpRect = compose.onNode(title).getUnclippedBoundsInRoot()

    private fun titleText(): TextLayoutResult {
        val node = compose.onNode(hasAnyAncestor(title) and hasText("", substring = true)).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** The title on the buttons' line: after back, clear of [nextControl], centred on the buttons, one line. */
    private fun assertTitle(name: String, nextControl: String) {
        compose.onNode(title and hasAnyAncestor(hasTestTag("chat-header"))).assertExists()
        compose.onNode(hasAnyAncestor(title) and hasText(name)).assertExists()
        val box = titleBounds()
        val back = targetOf("Back")
        val backGlyphRight = (back.left + back.right) / 2 + CursorDimens.iconButton / 2
        assertWithMessage("title starts right after back").that((box.left - backGlyphRight).value).isWithin(0.5f).of(0f)
        val text = compose.onNode(hasAnyAncestor(title) and hasText(name)).getUnclippedBoundsInRoot()
        assertWithMessage("title clear of $nextControl").that(text.right.value).isAtMost(targetOf(nextControl).left.value)
        assertWithMessage("title centred on the buttons").that(((box.top + box.bottom) / 2).value).isWithin(0.5f).of(((back.top + back.bottom) / 2).value)
        assertWithMessage("title lines").that(titleText().lineCount).isEqualTo(1)
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun assertEdges(controls: List<String>) {
        val bar = headerBounds()
        assertWithMessage("header top").that(bar.top.value).isWithin(0.5f).of(STATUS_BAR.value)
        assertWithMessage("header height").that((bar.bottom - bar.top).value).isWithin(0.5f).of(CursorDimens.chatHeaderHeight.value)
        assertThat(CursorDimens.chatHeaderHeight).isEqualTo(40.dp)
        controls.forEach { control ->
            compose.onNode(hasAnyAncestor(hasTestTag("chat-header")) and hasContentDescription(control)).assertExists()
            val target = targetOf(control)
            assertWithMessage("$control target height").that((target.bottom - target.top).value).isWithin(0.5f).of(CursorDimens.minTouchTarget.value)
            assertWithMessage("$control target top").that(target.top.value).isWithin(0.5f).of(STATUS_BAR.value)
            assertWithMessage("$control target reach over the transcript").that((target.bottom - bar.bottom).value).isWithin(0.5f).of(REACH.value)
        }
    }

    @Test
    fun `an ordinary chat - the controls and the name beside back, 40dp under the status bar, no repository line`() {
        open(agentName = CHAT)
        assertEdges(listOf("Back", "Open pull request", "Open panel", "More"))
        assertThat(label()).isEqualTo(CHAT)
        assertTitle(CHAT, nextControl = "Open pull request")
        assertThat(titleText().isLineEllipsized(0)).isFalse()
        compose.onNode(hasAnyAncestor(title) and hasContentDescription("Project")).assertDoesNotExist()
        assertThat(onScreen(CHAT_DETAIL)).isFalse()
        assertThat(onScreen("cesium")).isFalse()

        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(CHAT) and hasAnyAncestor(hasTestTag("conversation-panel"))).assertExists()
        compose.onNode(hasText(CHAT_DETAIL) and hasAnyAncestor(hasTestTag("conversation-panel"))).assertExists()
    }

    @Test
    fun `a tap on a target's reach over the transcript lands on the control, one past it on the transcript`() {
        open(agentName = CHAT)
        val bar = headerBounds()
        val back = targetOf("Back")
        fun tapUnderBack(below: Dp) {
            val x = (back.left + back.right) / 2
            val y = bar.bottom + below
            compose.onRoot().performTouchInput { click(Offset(x.value * density, y.value * density)) }
            compose.waitForIdle()
        }
        tapUnderBack(REACH + 4.dp)
        assertThat(backs).isEqualTo(0)
        tapUnderBack(REACH - 2.dp)
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `Rename stays in the menu`() {
        open(agentName = CHAT)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Rename").assertExists()
    }

    @Test
    fun `the menu reloads the transcript and has no Refresh, every other item in its order`() {
        open(agentName = CHAT)
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        val items = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } }
        assertThat(items).containsExactly("Unpin", "Rename", "Reload transcript", "Open on cursor.com", "Copy link", "Share…", "Share diagnostics", "Snooze", "Archive").inOrder()
    }

    @Test
    @Config(fontScale = 2f)
    fun `a Project's chat wears the same header, at the same height at the largest font`() {
        open(agentId = DemoData.PROJECT_ID)
        assertEdges(listOf("Back", "Open panel", "More"))
        assertThat(label()).isEqualTo(PROJECT)
        assertTitle(PROJECT, nextControl = "Open panel")
    }

    @Test
    fun `a Project's chat leads its name with the Project's icon`() {
        open(agentId = DemoData.PROJECT_ID)
        assertTitle(PROJECT, nextControl = "Open panel")
        val icon = compose.onNode(hasAnyAncestor(title) and hasContentDescription("Project")).getUnclippedBoundsInRoot()
        val name = compose.onNode(hasAnyAncestor(title) and hasText(PROJECT)).getUnclippedBoundsInRoot()
        assertThat(icon.right.value).isAtMost(name.left.value)
        assertThat(icon.left.value).isWithin(0.5f).of(titleBounds().left.value + 4f)
    }

    @Test
    fun `a long name is ellipsized on one line short of the right-side buttons`() {
        val graph = open(agentName = CHAT)
        runBlocking { graph.agents.rename(graph.agents.state.value.agents.first { it.name == CHAT }.id, LONG).getOrThrow() }
        compose.waitUntil(10_000) { onScreen(LONG) }
        assertEdges(listOf("Back", "Open pull request", "Open panel", "More"))
        assertTitle(LONG, nextControl = "Open pull request")
        assertThat(titleText().isLineEllipsized(0)).isTrue()
    }

    @Test
    fun `without back - a wide window beside the rail - the header has no name`() {
        open(agentName = CHAT, back = false)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNode(title).assertDoesNotExist()
        assertThat(label()).isEqualTo(CHAT)
        val bar = headerBounds()
        assertThat((bar.bottom - bar.top).value).isWithin(0.5f).of(CursorDimens.chatHeaderHeight.value)
    }

    private companion object {
        val STATUS_BAR: Dp = 24.dp
        val REACH: Dp = (CursorDimens.minTouchTarget - CursorDimens.iconButton) / 2
        const val CHAT = "Revenue Scaling Pipeline Research"
        const val CHAT_DETAIL = "cesium · cursor/revenue-pipeline-3f2a"
        const val PROJECT = "Cesium billing launch"
        const val LONG = "Revenue Scaling Pipeline Research across every regional billing ledger and the quarterly forecast"
    }
}
