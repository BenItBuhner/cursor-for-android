package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A `task` call as the web's Task card: the title, the cloud glyph, the state word, and where a tap goes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TaskRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun task(status: String = ToolCall.STATUS_COMPLETED, agentId: String? = null, isError: Boolean = false) = ToolCall(
        callId = "t1",
        name = "task_v2",
        kind = ToolKind.Task,
        status = status,
        summary = "Icon fidelity gaps: target and layered triangle",
        isError = isError,
        payload = ToolPayload.Subagent(description = "Icon fidelity gaps: target and layered triangle", agentId = agentId, transcriptPath = "/tmp/subagents/t1.md", durationMs = 45 * 60_000L + 9_000L),
    )

    private fun show(call: ToolCall) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(onOpenAgent = { opened += it })) {
                    ToolCallLine(call)
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a finished task reads Completed under its title with how long it ran`() {
        show(task())
        compose.onNodeWithTag("task-row").assertIsDisplayed()
        assertThat(shown("Icon fidelity gaps: target and layered triangle")).isTrue()
        assertThat(shown("Completed · 45m 9s")).isTrue()
        // A local subagent: no cloud glyph; the tap opens the details instead.
        assertThat(compose.onAllNodesWithContentDescription("Cloud agent").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("task-row").performClick()
        assertThat(shown("/tmp/subagents/t1.md")).isTrue()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a cloud subagent wears the cloud glyph and opens its chat`() {
        show(task(agentId = "bc-sub"))
        compose.onAllNodesWithContentDescription("Cloud agent")[0].assertIsDisplayed()
        compose.onNodeWithTag("task-row").performClick()
        assertThat(opened).containsExactly("bc-sub")
    }

    @Test
    fun `a running task says Working and a failed one Failed`() {
        show(task(status = ToolCall.STATUS_RUNNING))
        assertThat(shown("Working…")).isTrue()
        assertThat(shown("45m 9s")).isFalse()
    }

    @Test
    fun `a failed task says Failed`() {
        show(task(isError = true))
        assertThat(shown("Failed")).isTrue()
        assertThat(compose.onAllNodesWithTag("task-row").fetchSemanticsNodes()).hasSize(1)
    }
}
