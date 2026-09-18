package com.cursorforandroid.ui.panel

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the panel's chrome ends up against the window's system bars. The insets are dispatched to the Compose view
 * the way the window does it — a 24dp status bar, a 24dp gesture navigation bar — and the panel is composed the two
 * ways the app composes it: as the sheet its host slides in over the chat, on a phone and on a wide window, and as a
 * column beside the chat inside a shell that has consumed the status bar already, the shape the pane takes.
 *
 * What is held: the header's top edge (the title row with the close button, or the file viewer's row with its back
 * button) is never above the status bar's bottom edge, the sections never run under the navigation bar, the host's
 * surface still runs edge to edge behind both, and where the shell has consumed the status bar first the header sits
 * exactly one bar down, not two.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class PanelInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density: Float get() = compose.activity.resources.displayMetrics.density
    private val statusBarPx: Int get() = (STATUS_BAR_DP * density).toInt()
    private val navigationBarPx: Int get() = (NAVIGATION_BAR_DP * density).toInt()

    /** The panel as its host slides it in: the real sheet, open, over a stand-in for the chat. */
    @Composable
    private fun Sheet(state: PanelState) {
        CursorTheme(mode = ThemeMode.Dark) {
            SidePanel(
                state = rememberSidePanelState(SidePanelValue.Open),
                panelContent = { ConversationPanel(state, PanelActions.None, onClose = {}) },
            ) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("chat"))
            }
        }
    }

    /**
     * The panel as a pane beside the chat, inside a shell that already sits under the status bar: the shape a wide
     * window's shell gives it, and the case in which the panel must add nothing of its own for that bar.
     */
    @Composable
    private fun PaneInConsumingShell(state: PanelState) {
        CursorTheme(mode = ThemeMode.Dark) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("shell")) {
                Box(Modifier.weight(1f).fillMaxHeight().background(CursorTheme.colors.canvas).testTag("chat"))
                Box(Modifier.width(360.dp).fillMaxHeight().background(CursorTheme.colors.sidebar).testTag("pane")) {
                    ConversationPanel(state, PanelActions.None, onClose = {})
                }
            }
        }
    }

    private fun dispatchInsets(statusBar: Int = statusBarPx, navigationBar: Int = navigationBarPx) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setVisible(WindowInsetsCompat.Type.statusBars(), statusBar > 0)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBar > 0)
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

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun header(): Rect = compose.onAllNodes(hasTestTag("cursor-header")).onFirst().fetchSemanticsNode().boundsInRoot

    private fun rootBottom(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    /** The header starts at the status bar's edge and the sections end at the navigation bar's, whatever hosts them. */
    private fun assertChromeClearsTheBars(where: String, headerTopExpected: Float = statusBarPx.toFloat()) {
        // The close control lives in the header, wholly below the bar: the row the reader actually taps.
        val close = compose.onNodeWithContentDescription("Close panel").fetchSemanticsNode().boundsInRoot
        assertWithMessage("close button top ($where)").that(close.top).isAtLeast(statusBarPx.toFloat())
        val header = header()
        assertWithMessage("header top ($where)").that(header.top).isWithin(0.5f).of(headerTopExpected)
        assertWithMessage("header top ($where)").that(header.top).isAtLeast(statusBarPx.toFloat())
        assertWithMessage("sections bottom ($where)").that(bounds("panel-sections").bottom).isWithin(0.5f).of(rootBottom() - navigationBarPx)
    }

    @Test
    fun `sheet on a phone - the header sits under the status bar and the sections above the navigation bar`() {
        compose.setContent { Sheet(PanelFixtures.loaded()) }
        dispatchInsets()
        assertChromeClearsTheBars("phone sheet")
        // The sheet itself still runs edge to edge: its pane spans the whole window height, bars included.
        val sheet = compose.onNode(hasContentDescription("Dismiss panel")).fetchSemanticsNode().boundsInRoot
        assertThat(sheet.top).isWithin(0.5f).of(0f)
        assertThat(sheet.bottom).isWithin(0.5f).of(rootBottom())
        // Without bars there is nothing to clear, and the header is flush with the top.
        dispatchInsets(statusBar = 0, navigationBar = 0)
        assertThat(header().top).isWithin(0.5f).of(0f)
        assertThat(bounds("panel-sections").bottom).isWithin(0.5f).of(rootBottom())
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `sheet on a wide window - the same`() {
        compose.setContent { Sheet(PanelFixtures.loaded()) }
        dispatchInsets()
        assertChromeClearsTheBars("wide sheet")
    }

    @Test
    fun `sheet showing a file - the viewer's header sits under the status bar too`() {
        val file = RepoFile("app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt", "fun ThemeToggle() = Unit\n".toByteArray(), 25L, sha = "abc", downloadUrl = "https://raw.githubusercontent.com/x")
        val state = PanelFixtures.loaded().let { it.copy(browser = it.browser.copy(file = FileView.Repository(file))) }
        compose.setContent { Sheet(state) }
        dispatchInsets()
        assertThat(compose.onNodeWithContentDescription("Back to the panel").fetchSemanticsNode().boundsInRoot.top).isAtLeast(statusBarPx.toFloat())
        assertThat(header().top).isWithin(0.5f).of(statusBarPx.toFloat())
        assertThat(bounds("text-file").bottom).isWithin(0.5f).of(rootBottom() - navigationBarPx)
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun `pane inside a shell that consumed the status bar - one bar down, never two`() {
        compose.setContent { PaneInConsumingShell(PanelFixtures.loaded()) }
        dispatchInsets()
        // The shell took the status bar; the pane and its header add nothing more for it.
        assertThat(bounds("pane").top).isWithin(0.5f).of(statusBarPx.toFloat())
        assertChromeClearsTheBars("pane in a consuming shell")
        // And the chat beside it sees the same single bar.
        assertThat(bounds("chat").top).isWithin(0.5f).of(statusBarPx.toFloat())
    }

    private companion object {
        /** The reference device's bars at 420dpi: a 24dp status bar and a 24dp gesture navigation bar. */
        const val STATUS_BAR_DP = 24
        const val NAVIGATION_BAR_DP = 24
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
