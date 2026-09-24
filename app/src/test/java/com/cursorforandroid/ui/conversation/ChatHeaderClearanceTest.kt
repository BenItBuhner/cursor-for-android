package com.cursorforandroid.ui.conversation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.components.HeaderBandMillis
import com.cursorforandroid.ui.components.HeaderClearance
import com.cursorforandroid.ui.navigation.AppShell
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
 * A chat's header against the transcript's column, in the real screen under a 24dp status bar. Where both ends'
 * controls stand in the margins beside the column the header keeps no band: the transcript's viewport starts at the
 * status bar's edge, and the controls stay where they were, over the margin, taking their taps while a drag across
 * the column under their row moves the transcript. Where any control reaches the column — a phone, a narrow pane, the
 * Fold's inner screen beside the rail — the band is kept as it always was, the viewport starting under the header's
 * 40dp row. The answer follows the pane's width live (the rail's toggle, a rotation), easing between the two, and the
 * panel, which only ever covers the chat, leaves it be.
 *
 * The chat is given a pane of a set size in a window large enough for all of them, as the shell gives it the window
 * less the rail; the rail itself is the last test's, in the running shell.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1280dp-night-mdpi")
class ChatHeaderClearanceTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryOnLeakedExceptions()).around(compose)

    private val density: Float get() = compose.activity.resources.displayMetrics.density

    private var pane by mutableStateOf(TABLET_PANE)
    private var backs = 0
    private var sidebarOpens = 0

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(agentId: String, sidebar: Boolean = false) {
        val graph = demo()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.size(pane)) {
                        ConversationScreen(
                            graph, agentId,
                            onBack = if (sidebar) null else ({ backs++ }),
                            onOpenSidebar = if (sidebar) ({ sidebarOpens++ }) else null,
                        )
                    }
                }
            }
        }
        dispatchStatusBar()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("follow-up-composer")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { label() != "Chat" }
        compose.waitUntil(30_000) { compose.onAllNodes(transcript).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun demo(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        return graph
    }

    private fun dispatchStatusBar() {
        val px = (STATUS_BAR * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
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

    private fun label(): String? =
        compose.onNodeWithTag("chat-header").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private val transcript: SemanticsMatcher = hasTestTag("transcript")

    /** Where the transcript's viewport starts: the status bar's edge without the band, the header row's bottom with it. */
    private fun transcriptTop(): Float = compose.onNode(transcript).getUnclippedBoundsInRoot().top.value

    private fun headerBounds(): DpRect = compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot()

    private fun targetOf(control: String): DpRect =
        compose.onNode(hasAnyAncestor(hasTestTag("chat-header")) and hasContentDescription(control)).getUnclippedBoundsInRoot()

    private fun assertBandReleased() = assertWithMessage("transcript top (no band)").that(transcriptTop()).isWithin(0.5f).of(STATUS_BAR)

    private fun assertBandKept() = assertWithMessage("transcript top (band kept)").that(transcriptTop()).isWithin(0.5f).of(BAND_BOTTOM)

    /** The header row itself never moves: 40dp under the status bar, each target starting at the bar's edge. */
    private fun assertHeaderInPlace(controls: List<String>) {
        val bar = headerBounds()
        assertWithMessage("header top").that(bar.top.value).isWithin(0.5f).of(STATUS_BAR)
        assertWithMessage("header height").that((bar.bottom - bar.top).value).isWithin(0.5f).of(CursorDimens.chatHeaderHeight.value)
        controls.forEach { control ->
            assertWithMessage("$control target top").that(targetOf(control).top.value).isWithin(0.5f).of(STATUS_BAR)
        }
    }

    /** The column the transcript's rows and the composer take in a pane [width] wide: 640dp at most, 16dp gutters. */
    private fun columnIn(width: Float): ClosedFloatingPointRange<Float> {
        val column = minOf(width - 2 * 16f, CursorDimens.composerMaxWidth.value)
        return (width - column) / 2..(width + column) / 2
    }

    private fun assertClearOfColumn(controls: List<String>) {
        val column = columnIn(pane.width.value)
        controls.forEach { control ->
            val target = targetOf(control)
            assertWithMessage("$control target ${target.left}..${target.right} beside the column $column")
                .that(target.right.value <= column.start + 0.5f || target.left.value >= column.endInclusive - 0.5f).isTrue()
        }
    }

    /** The transcript's viewport top on every frame of a change of [pane] to [to], the clock stepped by hand. */
    private fun framesOfResize(to: DpSize): List<Float> {
        compose.mainClock.autoAdvance = false
        try {
            pane = to
            return List((HeaderBandMillis + 200) / 16) {
                compose.mainClock.advanceTimeByFrame()
                transcriptTop()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    /** Every frame between [from] and [to] on the way, never back, and enough of them in between to read as a motion. */
    private fun assertEases(frames: List<Float>, from: Float, to: Float) {
        assertWithMessage("frames $frames").that(frames.last()).isWithin(0.5f).of(to)
        val steps = frames.zipWithNext { a, b -> if (to < from) a - b else b - a }
        assertWithMessage("frames $frames only move toward $to").that(steps.all { it >= -0.01f }).isTrue()
        val between = frames.filter { it > minOf(from, to) + 1f && it < maxOf(from, to) - 1f }.distinct()
        assertWithMessage("frames $frames eased rather than jumped").that(between.size).isAtLeast(5)
    }

    @Test
    fun `the reach a control takes past its button is its touch target's, and a control only counts where it has a button`() {
        val column = listOf(HeaderClearance.Span(180f, 820f), HeaderClearance.Span(180f, 820f))
        fun overlaps(vararg controls: HeaderClearance.Span) = HeaderClearance.overlaps(controls.toList(), column)
        assertThat(HeaderClearance.ControlReach).isEqualTo(6.dp)
        assertThat(overlaps(HeaderClearance.Span(0f, 44f), HeaderClearance.Span(892f, 1001f))).isFalse()
        assertThat(overlaps(HeaderClearance.Span(0f, 44f), HeaderClearance.Span(819f, 1001f))).isTrue()
        assertThat(overlaps(HeaderClearance.Span(820f, 900f))).isFalse()
        assertThat(overlaps(HeaderClearance.Span(100f, 181f))).isTrue()
        assertThat(overlaps(HeaderClearance.Span(400f, 400f).reaching(6f))).isFalse()
        assertThat(HeaderClearance.overlaps(listOf(HeaderClearance.Span(0f, 44f)), listOf(HeaderClearance.Span(180f, 820f), HeaderClearance.Span(40f, 960f)))).isTrue()
    }

    @Test
    fun `beside wide margins the transcript reads from the status bar's edge, the controls where they were and taking their taps`() {
        open(REVENUE_ID)
        assertBandReleased()
        val controls = listOf("Back", "Open pull request", "Open panel", "More")
        assertHeaderInPlace(controls)
        assertClearOfColumn(controls)

        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a drag across the column under the header's row moves the transcript beneath it`() {
        pane = DpSize(1001.dp, 420.dp)
        open(DemoData.PROJECT_ID, sidebar = true)
        assertBandReleased()
        val texts = hasAnyAncestor(transcript) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        fun tops(): Map<String, Float> = compose.onAllNodes(texts, useUnmergedTree = true).fetchSemanticsNodes()
            .associate { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } to node.boundsInRoot.top / density }
        val before = tops()
        val x = pane.width.value / 2
        val y = STATUS_BAR + CursorDimens.chatHeaderHeight.value / 2
        compose.onRoot().performTouchInput { swipe(Offset(x * density, y * density), Offset(x * density, (y + 300f) * density), durationMillis = 400) }
        compose.waitForIdle()
        val after = tops()
        val moved = before.keys.intersect(after.keys).map { after.getValue(it) - before.getValue(it) }
        assertWithMessage("rows moved by $moved; before $before; after $after").that(moved.maxOrNull() ?: 0f).isGreaterThan(100f)
        assertThat(sidebarOpens).isEqualTo(0)
    }

    @Test
    fun `where the column reaches under the controls the band stays, the transcript starting under the header's row`() {
        pane = DpSize(700.dp, 1000.dp)
        open(REVENUE_ID)
        assertBandKept()
        assertHeaderInPlace(listOf("Back", "Open pull request", "Open panel", "More"))
    }

    @Test
    fun `the band follows the pane as it widens and narrows, easing between the two`() {
        pane = DpSize(700.dp, 800.dp)
        open(REVENUE_ID)
        assertBandKept()

        assertEases(framesOfResize(TABLET_PANE), from = BAND_BOTTOM, to = STATUS_BAR)
        assertHeaderInPlace(listOf("Back", "Open pull request", "Open panel", "More"))

        assertEases(framesOfResize(DpSize(700.dp, 800.dp)), from = STATUS_BAR, to = BAND_BOTTOM)
        assertHeaderInPlace(listOf("Back", "Open pull request", "Open panel", "More"))
    }

    @Test
    fun `turned on its side a tablet's chat gives its band back, and turned back takes it again`() {
        pane = DpSize(800.dp, 1280.dp)
        open(REVENUE_ID)
        // Upright, 800dp: the 640dp column's edge is at 720dp, and the pull request button's target starts at 692dp.
        assertBandKept()
        assertEases(framesOfResize(DpSize(1280.dp, 800.dp)), from = BAND_BOTTOM, to = STATUS_BAR)
        assertEases(framesOfResize(DpSize(800.dp, 1280.dp)), from = STATUS_BAR, to = BAND_BOTTOM)
    }

    @Test
    fun `the panel opening over the chat leaves the band as it was`() {
        open(REVENUE_ID)
        assertBandReleased()
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertBandReleased()
    }

    @Test
    fun `on the Fold's inner screen with the rail collapsed, the pull request button brings the column under the controls`() {
        pane = FOLD_PANE
        open(REVENUE_ID, sidebar = true)
        assertBandKept()
    }

    @Test
    fun `on the Fold's inner screen with the rail collapsed, a chat without one stands clear`() {
        pane = FOLD_PANE
        open(DemoData.PROJECT_ID, sidebar = true)
        assertBandReleased()
        assertClearOfColumn(listOf("Open sidebar", "Open panel", "More"))
    }

    /** The font scales no part of the row or the column's width, so the largest one changes nothing about the answer. */
    @Test
    @Config(fontScale = 2f)
    fun `at the largest font the tablet's answer is still the one measured`() {
        open(REVENUE_ID)
        assertBandReleased()
        assertClearOfColumn(listOf("Back", "Open pull request", "Open panel", "More"))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-420dpi")
    fun `a phone keeps the band, the slim header as it was`() {
        pane = DpSize(411.dp, 914.dp)
        open(REVENUE_ID)
        assertBandKept()
        assertHeaderInPlace(listOf("Back", "Open pull request", "Open panel", "More"))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-420dpi", fontScale = 2f)
    fun `a phone keeps the band at the largest font`() {
        pane = DpSize(411.dp, 914.dp)
        open(DemoData.PROJECT_ID)
        assertBandKept()
        assertHeaderInPlace(listOf("Back", "Open panel", "More"))
    }

    /** The rail's toggle in the running shell: open, the rail brings a 1000dp window's column to the header's buttons. */
    @Test
    @Config(qualifiers = "w1000dp-h720dp-night-mdpi")
    fun `in the running shell collapsing the rail gives the band back, and expanding it takes it again`() {
        val graph = demo()
        var deepLink by mutableStateOf<String?>(null)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = true,
                    deepLinkAgentId = deepLink,
                    onDeepLinkConsumed = { deepLink = null },
                )
            }
        }
        dispatchStatusBar()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Ask Cursor to build, fix bugs, explore", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        deepLink = REVENUE_ID
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat-header")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { label() != "Chat" }
        compose.waitUntil(30_000) { compose.onAllNodes(transcript).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertBandKept()

        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Open sidebar")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertBandReleased()
        assertHeaderInPlace(listOf("Open sidebar", "Open pull request", "Open panel", "More"))

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Open sidebar")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertBandKept()
    }

    private companion object {
        const val STATUS_BAR = 24f
        val BAND_BOTTOM = STATUS_BAR + CursorDimens.chatHeaderHeight.value

        /** A Pixel Tablet on its side (1280dp) less the expanded rail. */
        val TABLET_PANE = DpSize(1001.dp, 800.dp)

        /** A Pixel Fold's inner screen, the rail collapsed. */
        val FOLD_PANE = DpSize(841.dp, 701.dp)

        const val REVENUE_ID = "bc-demo-0002"
    }
}
