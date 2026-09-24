package com.cursorforandroid.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the composer shows under itself when a send did not go through: the account's refusal of a machine start as the
 * compact notice — its words, the `Asked:` line, the X — and every other reason, the documented create's refusal with
 * its Extended-mode line included, as the red line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposerErrorLineTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun show(error: String, asked: String?) {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ComposerErrorLine(error, asked, onDismiss = { dismissed++ }) } }
        compose.waitForIdle()
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a refused machine start is the compact notice, with the account's words and what was asked`() {
        show(
            "The requested self-hosted worker is not connected.",
            "POST /aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot → HTTP 400 failed_precondition",
        )

        compose.onNodeWithTag("composer-refusal").assertIsDisplayed()
        compose.onNodeWithTag("composer-refusal-title").assertIsDisplayed()
        compose.onNodeWithText("The requested self-hosted worker is not connected.").assertIsDisplayed()
        compose.onNodeWithText("Asked: POST /aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot → HTTP 400 failed_precondition").assertIsDisplayed()
        assertThat(shown("composer-error")).isFalse()
        compose.onNodeWithContentDescription("Close notice").performClick()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `the documented create's refusal of a machine's repository is the red line, with the Extended-mode line under Cursor's words`() {
        val words = "The source control provider for repository bennett/codex-poly-bot is not connected to Cursor. Connect it and try again.\n" +
            AgentRepository.MACHINE_NEEDS_EXTENDED
        show(words, asked = null)

        compose.onNodeWithTag("composer-error").assertIsDisplayed()
        compose.onNodeWithText(words).assertIsDisplayed()
        assertThat(shown("composer-refusal")).isFalse()
    }
}
