package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one-time notice an upgraded install sees in the shell. The session is the demo's (nothing here needs a
 * network) while the shell is told it shows a real account, which is the case the notice is for.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ExtendedModeNoticeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shell(noticePending: Boolean, isDemo: Boolean): AppGraph {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.session.enterDemo()
            graph.prefs.setExtendedModeIntroduced(noticePending = noticePending)
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Key", "alex@example.com", "Alex", "Rivera", null),
                    isDemo = isDemo,
                    wide = false,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        return graph
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun noticeShown() = compose.onAllNodes(hasTestTag(ExtendedModeTags.NOTICE)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an owed notice is shown once and opens Settings when asked`() {
        val graph = shell(noticePending = true, isDemo = false)
        compose.waitUntil(10_000) { noticeShown() }
        compose.onNodeWithText(ExtendedModeCopy.NOTICE_BODY).assertExists()

        compose.onNodeWithText(ExtendedModeCopy.NOTICE_OPEN_SETTINGS).performClick()

        compose.waitUntil(10_000) { !noticeShown() }
        compose.waitUntil(10_000) { onScreen("Advanced") }
        assertThat(runBlocking { graph.extendedMode.noticePending.first() }).isFalse()
    }

    @Test
    fun `dismissing the notice settles it`() {
        val graph = shell(noticePending = true, isDemo = false)
        compose.waitUntil(10_000) { noticeShown() }

        compose.onNodeWithText(ExtendedModeCopy.NOTICE_DISMISS).performClick()

        compose.waitUntil(10_000) { !noticeShown() }
        assertThat(runBlocking { graph.extendedMode.noticePending.first() }).isFalse()
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()
    }

    @Test
    fun `nothing is shown when no notice is owed`() {
        shell(noticePending = false, isDemo = false)
        compose.waitForIdle()
        assertThat(noticeShown()).isFalse()
    }

    @Test
    fun `the demo is never shown the notice`() {
        val graph = shell(noticePending = true, isDemo = true)
        compose.waitForIdle()
        assertThat(noticeShown()).isFalse()
        assertThat(runBlocking { graph.extendedMode.noticePending.first() }).isTrue()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
