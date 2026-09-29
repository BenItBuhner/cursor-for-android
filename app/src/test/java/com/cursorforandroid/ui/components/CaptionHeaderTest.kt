package com.cursorforandroid.ui.components

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The headers in a desktop window's caption bar, as [CaptionBarHost] provides it: a 40dp bar with the app menu over
 * the first 160dp and the window controls over the last 200dp of a 1000dp window (mdpi, so pixels are dp). Each
 * header stands in the bar's row, its buttons centred on the bar and never under a system control, wherever the
 * header is in the window; with no caption bar the headers keep their usual height.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1000dp-h700dp-mdpi")
class CaptionHeaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appMenu = IntRect(0, 0, 160, 40)
    private val windowControls = IntRect(800, 0, 1000, 40)
    private val bar = CaptionBar(40, listOf(appMenu, windowControls))

    private fun header(bar: CaptionBar?, railWidth: Int = 0, chat: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalCaptionBar provides bar) {
                    Row(Modifier.fillMaxSize()) {
                        if (railWidth > 0) Box(Modifier.width(railWidth.dp))
                        Box(Modifier.weight(1f)) {
                            val leading: @Composable RowScope.() -> Unit = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) }
                            val trailing: @Composable RowScope.() -> Unit = { FlatIconButton(CursorIcons.More, "More", onClick = {}) }
                            if (chat) ChatHeader("Chat", leading = leading, trailing = trailing) else CursorHeader(leading = leading, trailing = trailing)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String): DpRect = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()

    private fun assertClearOfControls(description: String) {
        val button = bounds(description)
        for (control in bar.controls) {
            val overlaps = button.left.value < control.right && control.left < button.right.value
            assertWithMessage("$description at $button under the system control at $control").that(overlaps).isFalse()
        }
    }

    private fun assertCentredOnBar(description: String) {
        val button = bounds(description)
        assertThat((button.top + button.bottom).value / 2).isWithin(0.5f).of(bar.heightPx / 2f)
    }

    @Test
    fun `a full-width header stands in the bar clear of the app menu and the window controls`() {
        header(bar)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertClearOfControls("Open sidebar")
        assertClearOfControls("More")
        assertCentredOnBar("Open sidebar")
        assertCentredOnBar("More")
    }

    @Test
    fun `a chat header stands in the bar, and beside the rail only the window controls push it in`() {
        header(bar, railWidth = 300, chat = true)
        assertThat(compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertThat(bounds("Open sidebar").left).isLessThan(310.dp)
        assertClearOfControls("More")
        assertCentredOnBar("More")
    }

    @Test
    fun `a chat header's title stands beside back in the bar, centred on it and short of the window controls`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalCaptionBar provides bar) {
                    ChatHeader(
                        "Chat",
                        title = "A chat whose name runs on long enough to reach the window's own controls at the end",
                        leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = {}) },
                        trailing = { FlatIconButton(CursorIcons.More, "More", onClick = {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("chat-header-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat((title.top + title.bottom).value / 2).isWithin(0.5f).of(bar.heightPx / 2f)
        val back = bounds("Back")
        assertThat(title.left.value).isGreaterThan((back.left + back.right).value / 2)
        assertThat(title.left.value).isAtLeast(appMenu.right.toFloat())
        assertThat(title.right.value).isAtMost(bounds("More").left.value)
        assertThat(title.right.value).isAtMost(windowControls.left.toFloat())
        assertClearOfControls("Back")
        assertClearOfControls("More")
        assertCentredOnBar("Back")
    }

    @Test
    fun `the rail's header stands in the bar clear of the app menu, its logo left to the system's`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalCaptionBar provides bar) {
                    Sidebar(
                        state = AgentListUiState(hasLoaded = true),
                        user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                        isDemo = true,
                        selectedAgentId = null,
                        selectedDestination = null,
                        onQueryChange = {},
                        callbacks = SidebarCallbacks(
                            onNewChat = {},
                            onSettings = {},
                            onCustomize = {},
                            onToggleSidebar = {},
                            onRefresh = {},
                            rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        ),
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
        // The window reports the bar as an inset too, as a desktop window does; the rail must not pad by it as well.
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 40, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), insets)
        }
        compose.waitForIdle()
        for (button in listOf("New chat", "Search chats", "Toggle sidebar")) {
            assertClearOfControls(button)
            assertCentredOnBar(button)
        }
        compose.onNodeWithContentDescription("Cursor").assertDoesNotExist()
    }

    @Test
    fun `without a caption bar the headers keep their usual rows`() {
        header(null)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(CursorDimens.headerHeight)
        assertThat(bounds("Open sidebar").left).isLessThan(20.dp)
        assertThat(bounds("More").right).isGreaterThan(980.dp)
    }
}
