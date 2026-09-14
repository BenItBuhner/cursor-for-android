package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's Extended-mode controls: the Overview's run controls and the Pending question section answering — each
 * reaching the panel's actions, and each read-only or absent with the surface off. The queue is not here: queued
 * follow-ups live above the composer (see `AccountQueueRowsTest`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PanelFixtures.extendedRunning())

    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>) { asked += "answer:$callId:${answers.joinToString("|") { it.questionId + "=" + it.selectedOptionIds.joinToString(",") + (it.freeformText ?: "") }}" }
        override fun pauseRun() { asked += "pause" }
        override fun resumeRun() { asked += "resume" }
        override fun stopRun() { asked += "stop" }
        override fun wake() { asked += "wake" }
        override fun refreshSideChats() { asked += "side-chats" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag(tag))

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the Overview's controls hold, stop and wake the run, and follow its state`() {
        show()
        // Running: pause and stop are offered, wake is not.
        scrollTo("run-controls")
        compose.onAllNodesWithTag("control-Pause")[0].assertIsEnabled().performClick()
        compose.onAllNodesWithTag("control-Stop")[0].performClick()
        compose.onAllNodesWithTag("control-Wake")[0].assertIsNotEnabled()
        assertThat(asked).containsAtLeast("pause", "stop").inOrder()

        // Held from here: the row offers resume.
        state = state.copy(controls = state.controls.copy(isPaused = true))
        compose.waitForIdle()
        compose.onAllNodesWithTag("control-Resume")[0].performClick()
        assertThat(asked).contains("resume")

        // Idle: nothing to pause or stop, the machine can be woken.
        state = PanelFixtures.extendedRunning().copy(runStatus = RunStatus.FINISHED, isStreaming = false, agent = PanelFixtures.agent, controls = ConversationControls(queueLoad = QueueLoad.Loaded))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("control-Stop").fetchSemanticsNodes()).isEmpty()
        compose.onAllNodesWithTag("control-Wake")[0].assertIsEnabled().performClick()
        assertThat(asked).contains("wake")
    }

    @Test
    fun `the queue is not a panel section, in either mode`() {
        // Two follow-ups wait on the account, and the panel says nothing about them: they sit above the composer.
        show()
        assertThat(shown("Queue and steering")).isFalse()
        assertThat(shown("Queued on your account")).isFalse()
        assertThat(shown("Then add a test for the light theme")).isFalse()
        assertThat(compose.onAllNodesWithTag("steer-field").fetchSemanticsNodes()).isEmpty()
        state = state.copy(capabilities = Capabilities.DOCUMENTED)
        compose.waitForIdle()
        assertThat(shown("Queue and steering")).isFalse()
        assertThat(PanelRegistry.default().sections.map { it.title }).doesNotContain("Queue and steering")
    }

    @Test
    fun `the pending question is answerable from the panel and reads as sent once the account took it`() {
        show()
        scrollTo("section-PendingQuestion")
        assertThat(shown("Waiting")).isTrue()
        compose.onNode(hasTestTag("option-chip") and hasText("All of them")).performClick()
        compose.onNode(hasTestTag("option-chip") and hasText("Next deploy")).performClick()
        compose.onNodeWithTag("send-answer").performClick()
        assertThat(asked).contains("answer:ask-1:q-scope=all|q-when=deploy")
        // No pointer at cursor.com while the answer can be sent from here.
        assertThat(shown("Answer on cursor.com")).isFalse()

        state = state.copy(controls = state.controls.copy(answeredCallIds = setOf("ask-1")))
        compose.waitForIdle()
        compose.onNodeWithText("Answer sent").assertIsDisplayed()
        assertThat(shown("Answered")).isTrue()
    }

    @Test
    fun `with the surfaces off the same chat shows a read-only card and no controls, and calls nothing`() {
        state = state.copy(capabilities = Capabilities.DOCUMENTED)
        show()
        scrollTo("section-PendingQuestion")
        assertThat(shown("Answer on cursor.com")).isTrue()
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("run-controls").fetchSemanticsNodes()).isEmpty()
        // The card is the whole section: nothing under it names the endpoint an answer would take.
        assertThat(shown("SubmitInteractionResponseBackgroundComposer")).isFalse()
        assertThat(shown("Needs Extended mode")).isFalse()
        assertThat(asked.filterNot { it == "side-chats" }).isEmpty()
    }

    @Test
    fun `without a question there is no Pending question section, and the demo keeps the chips read-only`() {
        state = PanelFixtures.loaded().copy(capabilities = Capabilities.EXTENDED)
        show()
        assertThat(compose.onAllNodesWithTag("section-PendingQuestion").fetchSemanticsNodes()).isEmpty()
        assertThat(shown("The agent isn't waiting on a question")).isFalse()

        state = PanelFixtures.extendedRunning().copy(isDemo = true)
        compose.waitForIdle()
        scrollTo("section-PendingQuestion")
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("run-controls").fetchSemanticsNodes()).isEmpty()
    }
}
