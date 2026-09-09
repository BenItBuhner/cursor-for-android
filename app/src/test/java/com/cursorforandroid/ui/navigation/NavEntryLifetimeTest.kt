package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What [CursorNavHost] holds for an entry, and for how long. Only two entries are ever composed, so disposal alone
 * cannot decide when a screen's state is finished with — the stack has to.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NavEntryLifetimeTest {

    class Sentinel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sentinels = mutableMapOf<String, Sentinel>()

    private fun host(stack: NavStack) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorNavHost(stack) { screen ->
                    val sentinel: Sentinel = viewModel()
                    sentinels[screen.route] = sentinel
                    Text(screen.route)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun navigate(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    @Test
    fun `an entry dropped from the middle of the stack releases its view models`() {
        val stack = NavStack(Screen.Home)
        host(stack)
        navigate { stack.push(Screen.Settings) }
        navigate { stack.push(Screen.Agent("bc-1")) }
        assertThat(sentinels.keys).containsExactly("home", "settings", "agent/bc-1")
        assertThat(sentinels.getValue("settings").cleared).isFalse()

        // Settings is not composed here — it is under the chat — so nothing was disposed when it left the stack.
        navigate { stack.resetTo(Screen.Home) }
        assertThat(sentinels.getValue("settings").cleared).isTrue()
        assertThat(sentinels.getValue("home").cleared).isFalse()
    }

    @Test
    fun `a popped entry releases its view models and the one revealed keeps its own`() {
        val stack = NavStack(Screen.Home)
        host(stack)
        navigate { stack.push(Screen.Settings) }
        val settings = sentinels.getValue("settings")
        navigate { stack.pop() }
        assertThat(settings.cleared).isTrue()
        assertThat(sentinels.getValue("home").cleared).isFalse()
    }

    @Test
    fun `an entry still animating out is not released early`() {
        val stack = NavStack(Screen.Home)
        host(stack)
        navigate { stack.push(Screen.Settings) }
        navigate { stack.push(Screen.Agent("bc-1")) }
        val chat = sentinels.getValue("agent/bc-1")

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.resetTo(Screen.Home) }
        compose.mainClock.advanceTimeBy(64)
        // The chat is on screen giving way to Home, so it keeps its view models until the transition ends.
        assertThat(chat.cleared).isFalse()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(chat.cleared).isTrue()
    }
}
