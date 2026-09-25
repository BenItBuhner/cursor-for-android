package com.cursorforandroid.ui.navigation

import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The composer's "+" menu and the keyboard, in the running shell on the demo. The menu's window takes focus, and the
 * platform puts the keyboard away whenever a window that could take it gains focus with no text field of its own
 * focused — Robolectric does not play that part, so the menu's window is held to the platform's own test for it
 * ([WindowManager.LayoutParams.mayUseInputMethod]). Up, the menu leaves the composer its focus and the keyboard, and
 * its pages and pickers do too; put away with nothing picked, it hands both back to the composer should anything have
 * taken them meanwhile. The Skills search, once tapped, takes the keyboard for itself. The sheets' own keyboard
 * handling is [SheetKeyboardFlowTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PlusMenuKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enterDemo() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.enterDemo() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = false,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private val imm get() = compose.activity.getSystemService(InputMethodManager::class.java)

    private fun softInputVisible(): Boolean = shadowOf(imm).isSoftInputVisible

    private val written: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasText(Draft))

    private fun fieldText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun startWriting() {
        val composer = compose.onNode(hasSetTextAction() and hasText(PLACEHOLDER, substring = true))
        composer.performClick()
        composer.performTextInput(Draft)
        written.assertIsFocused()
        compose.waitUntil(5_000) { softInputVisible() }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitUntil(5_000) { onScreen("Skills") && menuWindow() != null }
        compose.waitForIdle()
    }

    /** The windows the app has added, each with the parameters it was last laid out with. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<Pair<View, WindowManager.LayoutParams>> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        fun <T> field(name: String) = global.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(global) as T
        val views = field<List<View>>("mViews")
        val params = field<List<WindowManager.LayoutParams>>("mParams")
        return views.zip(params)
    }

    private fun menuWindow(): Pair<View, WindowManager.LayoutParams>? =
        windows().lastOrNull { (view, _) -> view.javaClass.name.endsWith("PopupLayout") && view.isAttachedToWindow }

    private fun menuFlags(): Int = checkNotNull(menuWindow()).second.flags

    /** What the platform asks of a window gaining focus before it puts the keyboard away for it. */
    private fun menuWouldTakeKeyboard(): Boolean = WindowManager.LayoutParams.mayUseInputMethod(menuFlags())

    private fun assertMenuLeavesKeyboard() {
        assertThat(menuFlags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isEqualTo(0)
        assertThat(menuWouldTakeKeyboard()).isFalse()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
    }

    /** The platform's part while the menu holds focus, which Robolectric leaves out: the keyboard away, the field let go. */
    private fun keyboardTakenUnderMenu() {
        compose.runOnUiThread {
            imm.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            compose.activity.window.decorView.findFocus()?.clearFocus()
        }
        compose.waitForIdle()
        written.assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
    }

    private fun assertComposerHasKeyboard() {
        compose.waitUntil(5_000) { menuWindow() == null }
        compose.waitUntil(5_000) { runCatching { written.assertIsFocused() }.isSuccess }
        compose.waitUntil(5_000) { softInputVisible() }
        assertThat(fieldText(written)).isEqualTo(Draft)
    }

    @Test
    fun `the plus menu and its pages leave the composer its focus and its keyboard`() {
        startWriting()
        openMenu()
        assertMenuLeavesKeyboard()

        compose.onNodeWithText("MCP Servers").performClick()
        compose.waitUntil(5_000) { onScreen("Add MCP server") }
        compose.waitForIdle()
        assertMenuLeavesKeyboard()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Skills").performClick()
        compose.waitUntil(5_000) { onScreen("Search or type a skill name") }
        compose.waitForIdle()
        assertMenuLeavesKeyboard()
    }

    @Test
    fun `picking the gallery from the plus menu leaves the composer its focus and its keyboard`() {
        startWriting()
        openMenu()
        compose.onNodeWithText("Images", substring = true).performClick()
        compose.waitUntil(5_000) { menuWindow() == null }
        compose.waitForIdle()
        written.assertIsFocused()
        assertThat(softInputVisible()).isTrue()
    }

    @Test
    fun `the plus menu put away by back with nothing picked hands the composer its focus and keyboard back`() {
        startWriting()
        openMenu()
        keyboardTakenUnderMenu()

        val popup = checkNotNull(menuWindow()).first
        compose.runOnUiThread {
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        assertComposerHasKeyboard()
    }

    @Test
    fun `the plus menu put away by a tap outside it hands the composer its focus and keyboard back`() {
        startWriting()
        openMenu()
        keyboardTakenUnderMenu()

        val popup = checkNotNull(menuWindow()).first
        compose.runOnUiThread {
            val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
            popup.dispatchTouchEvent(down)
            down.recycle()
        }
        assertComposerHasKeyboard()
    }

    @Test
    fun `a composer that was not being written in is not handed the keyboard when the menu is put away`() {
        openMenu()
        val popup = checkNotNull(menuWindow()).first
        compose.runOnUiThread {
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitUntil(5_000) { menuWindow() == null }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText(PLACEHOLDER, substring = true)).assertIsNotFocused()
        assertThat(softInputVisible()).isFalse()
    }

    @Test
    fun `the skills search takes the keyboard for itself once tapped`() {
        startWriting()
        openMenu()
        compose.onNodeWithText("Skills").performClick()
        compose.waitUntil(5_000) { onScreen("Search or type a skill name") }
        compose.waitForIdle()
        assertThat(menuWouldTakeKeyboard()).isFalse()

        val search = compose.onNode(hasSetTextAction() and hasText("Search or type a skill name"))
        search.performClick()
        compose.waitUntil(5_000) { menuWouldTakeKeyboard() }
        compose.waitUntil(5_000) { runCatching { search.assertIsFocused() }.isSuccess }
    }

    private companion object {
        const val PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val Draft = "Half a thought about the"
    }
}
