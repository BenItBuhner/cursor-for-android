package com.cursorforandroid.ui.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The run footer is duration and status only. The header already names the branch and owns the pull-request
 * button; a pill under every reply repeating either is what #45 left behind and what this chat asked gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RunFooterTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(item: RunFooter) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { TimelineItemView(item) }
        }
    }

    private fun footer(
        status: RunStatus = RunStatus.FINISHED,
        durationMs: Long? = 185_000L,
        branch: String? = "cursor/device-options-dropdown-c4f8",
        prUrl: String? = "https://github.com/bennett/cursor-for-android/pull/64",
    ) = RunFooter(
        id = "run-1",
        runId = "run-1",
        status = status,
        durationMs = durationMs,
        branches = listOf(GitBranch("github.com/bennett/cursor-for-android", branch, prUrl)),
    )

    @Test
    fun `a finished run shows how long it worked and not the branch or pull request`() {
        show(footer())
        compose.onNodeWithText("Worked").assertIsDisplayed()
        compose.onNodeWithText("3m 5s").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("cursor/device-options-dropdown-c4f8").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("pull/64", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a failed run still names the ending and still hides the branch`() {
        show(footer(status = RunStatus.ERROR, durationMs = 45_000L))
        compose.onNodeWithText("Failed after").assertIsDisplayed()
        compose.onNodeWithText("45s").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("cursor/device-options-dropdown-c4f8").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a finished run with no duration paints nothing`() {
        show(footer(durationMs = null))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("Worked").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("cursor/device-options-dropdown-c4f8").fetchSemanticsNodes()).isEmpty()
    }
}
