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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SteerOutcome
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
 * The panel's Extended-mode controls: the Queue and steering section with the run's controls, the steer line and
 * the account's queue, the Overview's controls, and the Pending question section answering — each reaching the
 * panel's actions, and each a named state with the surface off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PanelFixtures.extendedRunning())

    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun refreshQueue() { asked += "refresh" }
        override fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>) { asked += "answer:$callId:${answers.joinToString("|") { it.questionId + "=" + it.selectedOptionIds.joinToString(",") + (it.freeformText ?: "") }}" }
        override fun steer(text: String) { asked += "steer:$text" }
        override fun pauseRun() { asked += "pause" }
        override fun resumeRun() { asked += "resume" }
        override fun stopRun() { asked += "stop" }
        override fun wake() { asked += "wake" }
        override fun queueSendNow(followupId: String) { asked += "now:$followupId" }
        override fun queueDelete(followupId: String) { asked += "delete:$followupId" }
        override fun queueMove(followupId: String, up: Boolean) { asked += "move:$followupId:${if (up) "up" else "down"}" }
        override fun queueUpdate(followupId: String, text: String) { asked += "update:$followupId:$text" }
        override fun queueMarkEditing(followupId: String, editing: Boolean) { asked += "editing:$followupId:$editing" }
        override fun queueSteerNow(followupId: String) { asked += "promote:$followupId" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag(tag))

    private fun open(section: PanelSectionId) {
        scrollTo("section-${section.name}")
        compose.onNodeWithTag("section-${section.name}").performClick()
        // What a section asks for on opening runs an effect later; let it.
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the queue section lists the account's queue with every edit, and opening it re-reads the queue`() {
        show()
        open(PanelSectionId.Queue)
        assertThat(asked).contains("refresh")
        scrollTo("queue-section")
        assertThat(shown("Queued on your account · 2")).isTrue()
        val rows = compose.onAllNodesWithTag("queued-followup").fetchSemanticsNodes()
        assertThat(rows).hasSize(2)
        assertThat(shown("Then add a test for the light theme")).isTrue()
        assertThat(shown("Being edited on another device")).isTrue()
        assertThat(shown("from glass")).isTrue()

        // The first row cannot move up, the last cannot move down.
        compose.onAllNodesWithContentDescription("Move up")[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Move down")[1].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Move down")[0].performClick()
        compose.onAllNodesWithContentDescription("Move up")[1].performClick()
        compose.onAllNodesWithTag("queued-send-now")[1].performClick()
        compose.onAllNodesWithTag("queued-steer")[0].performClick()
        compose.onAllNodesWithContentDescription("Remove queued follow-up")[1].performClick()
        assertThat(asked).containsAtLeast("move:fu-1:down", "move:fu-2:up", "now:fu-2", "promote:fu-1", "delete:fu-2").inOrder()
    }

    @Test
    fun `editing a queued message flags it on the account, and saving rewords it`() {
        show()
        open(PanelSectionId.Queue)
        scrollTo("queued-followup")
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        assertThat(asked).contains("editing:fu-1:true")
        compose.onNodeWithTag("queued-edit").performTextInput(", and dark")
        compose.onNodeWithTag("queued-save").performClick()
        assertThat(asked.last()).isEqualTo("update:fu-1:Then add a test for the light theme, and dark")

        // Cancelling hands the flag back without a change.
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(asked.last()).isEqualTo("editing:fu-1:false")
    }

    @Test
    fun `the steer line delivers into the running turn and shows the account's word, and the controls hold or wake the run`() {
        show()
        open(PanelSectionId.Queue)
        scrollTo("steer-field")
        assertThat(shown(SteerOutcome.QUEUED.message)).isTrue()
        compose.onNodeWithTag("steer-send").assertIsNotEnabled()
        compose.onNodeWithTag("steer-field").performTextInput("Use the v2 endpoint")
        compose.onNodeWithTag("steer-send").assertIsEnabled()
        compose.onNodeWithTag("steer-send").performClick()
        assertThat(asked).contains("steer:Use the v2 endpoint")

        // Running: pause and stop are offered, wake is not (the list composes the rows in view: the Queue section's here).
        scrollTo("run-controls")
        compose.onAllNodesWithTag("control-Pause")[0].assertIsEnabled().performClick()
        compose.onAllNodesWithTag("control-Stop")[0].performClick()
        compose.onAllNodesWithTag("control-Wake")[0].assertIsNotEnabled()
        assertThat(asked).containsAtLeast("pause", "stop").inOrder()

        // Held from here: the row offers resume, and the hint says so.
        state = state.copy(controls = state.controls.copy(isPaused = true))
        compose.waitForIdle()
        compose.onAllNodesWithTag("control-Resume")[0].performClick()
        assertThat(asked).contains("resume")
        assertThat(shown("Paused · 2 queued")).isTrue()

        // The Overview wears the same row, first in the panel.
        scrollTo("section-Header")
        compose.onAllNodesWithTag("run-controls")[0].assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag("run-controls").fetchSemanticsNodes().size).isAtLeast(1)

        // Idle: nothing to pause or stop, the machine can be woken.
        state = PanelFixtures.extendedRunning().copy(runStatus = RunStatus.FINISHED, isStreaming = false, agent = PanelFixtures.agent, controls = ConversationControls(queueLoad = QueueLoad.Loaded))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("control-Stop").fetchSemanticsNodes()).isEmpty()
        compose.onAllNodesWithTag("control-Wake")[0].assertIsEnabled().performClick()
        assertThat(asked).contains("wake")
        scrollTo("queue-section")
        assertThat(shown("Nothing queued")).isTrue()
        assertThat(shown("Steering needs a turn under way")).isTrue()
    }

    @Test
    fun `the queue names each degraded state and offers a retry`() {
        state = state.copy(controls = ConversationControls(queueLoad = QueueLoad.Unavailable("Cursor changed a private endpoint")))
        show()
        open(PanelSectionId.Queue)
        scrollTo("queue-unavailable")
        assertThat(shown("The queue can't be read")).isTrue()
        assertThat(shown("Cursor changed a private endpoint")).isTrue()
        compose.onNodeWithText("Retry").performClick()
        assertThat(asked.count { it == "refresh" }).isAtLeast(2)

        state = state.copy(controls = ConversationControls(queueLoad = QueueLoad.Loading))
        compose.waitForIdle()
        assertThat(shown("Reading the queue…")).isTrue()
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
    fun `with the surfaces off the same chat shows read-only cards and the named placeholders, and calls nothing`() {
        state = state.copy(capabilities = Capabilities.DOCUMENTED)
        show()
        scrollTo("section-PendingQuestion")
        assertThat(shown("Answer on cursor.com")).isTrue()
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("run-controls").fetchSemanticsNodes()).isEmpty()
        open(PanelSectionId.Queue)
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("ListPendingFollowups", substring = true))
        assertThat(shown("Needs Extended mode")).isTrue()
        assertThat(compose.onAllNodesWithTag("queue-section").fetchSemanticsNodes()).isEmpty()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `in Extended mode without a question the section says nothing is waiting, and the demo keeps the chips read-only`() {
        state = PanelFixtures.loaded().copy(capabilities = Capabilities.EXTENDED)
        show()
        scrollTo("section-PendingQuestion")
        assertThat(shown("The agent isn't waiting on a question")).isTrue()
        assertThat(shown("SubmitInteractionResponseBackgroundComposer")).isFalse()

        state = PanelFixtures.extendedRunning().copy(isDemo = true)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("run-controls").fetchSemanticsNodes()).isEmpty()
    }
}
