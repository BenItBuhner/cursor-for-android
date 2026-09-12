package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ConversationControls
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

/**
 * The agent's question as a card: read-only in default mode, and in Extended mode a form — one choice per question,
 * or any number where allowed, a line for an answer of one's own, and a send that hands the answers over in the
 * shape `SubmitInteractionResponseBackgroundComposer` takes; then "sent" until the stream shows the call finished.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QuestionCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val question = ToolPayload.Question(
        title = "Before I change the schema",
        questions = listOf(
            ToolPayload.Question.Item("q-scope", "Which tables should the migration touch?", listOf(ToolPayload.Question.Option("users", "users"), ToolPayload.Question.Option("orders", "orders"), ToolPayload.Question.Option("all", "All of them")), allowMultiple = true),
            ToolPayload.Question.Item("q-when", "Run it now or at the next deploy?", listOf(ToolPayload.Question.Option("now", "Now"), ToolPayload.Question.Option("deploy", "Next deploy"))),
        ),
    )

    private val sent = mutableListOf<List<ToolPayload.Question.Answer>>()
    private var answered by mutableStateOf(false)
    private var answering by mutableStateOf(false)

    private fun show(interactive: Boolean, pending: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                QuestionCard(
                    question,
                    pending = pending,
                    onOpenInBrowser = {},
                    onAnswer = if (interactive) ({ sent += it }) else null,
                    answered = answered,
                    answering = answering,
                )
            }
        }
    }

    private fun chip(label: String) = compose.onNode(hasTestTag("option-chip") and hasText(label))

    @Test
    fun `read-only, the chips are the choices on offer and the card points at cursor com`() {
        show(interactive = false)

        compose.onNodeWithText("Waiting for your answer").assertIsDisplayed()
        compose.onNodeWithText("Answer on cursor.com").assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag("option-chip").fetchSemanticsNodes()).hasSize(5)
        assertThat(compose.onAllNodesWithTag("answer-field").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        // The chips are not controls: tapping one selects nothing.
        chip("users").performClick()
        assertThat(compose.onAllNodes(isSelected()).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `answerable, a choice per question is required before the send is offered`() {
        show(interactive = true)

        compose.onNodeWithTag("send-answer").assertIsNotEnabled()
        assertThat(compose.onAllNodesWithText("Answer on cursor.com").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("answer-field").fetchSemanticsNodes()).hasSize(2)

        // Any number where the question allows it; the second question takes one.
        chip("users").performClick()
        chip("orders").performClick()
        chip("users").assertIsSelected()
        chip("orders").assertIsSelected()
        compose.onNodeWithTag("send-answer").assertIsNotEnabled()
        chip("Now").performClick()
        chip("Next deploy").performClick()
        chip("Next deploy").assertIsSelected()
        assertThat(compose.onAllNodes(isSelected() and hasText("Now")).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("send-answer").assertIsEnabled()

        compose.onNodeWithTag("send-answer").performClick()
        assertThat(sent).containsExactly(
            listOf(
                ToolPayload.Question.Answer("q-scope", listOf("users", "orders")),
                ToolPayload.Question.Answer("q-when", listOf("deploy")),
            ),
        )
    }

    @Test
    fun `an answer of one's own stands in for a choice, and a tapped choice comes off again`() {
        show(interactive = true)

        chip("All of them").performClick()
        chip("All of them").performClick()
        assertThat(compose.onAllNodes(isSelected()).fetchSemanticsNodes()).isEmpty()
        compose.onAllNodesWithTag("answer-field")[0].performTextInput("Only the audit tables")
        compose.onAllNodesWithTag("answer-field")[1].performTextInput("  After the freeze  ")
        compose.onNodeWithTag("send-answer").assertIsEnabled()

        compose.onNodeWithTag("send-answer").performClick()
        assertThat(sent.single()).containsExactly(
            ToolPayload.Question.Answer("q-scope", emptyList(), "Only the audit tables"),
            ToolPayload.Question.Answer("q-when", emptyList(), "After the freeze"),
        ).inOrder()
    }

    @Test
    fun `while the answer is out the send waits, and once the account took it the card says so`() {
        answering = true
        show(interactive = true)
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithTag("send-answer").assertIsNotEnabled()

        answering = false
        answered = true
        compose.waitForIdle()
        compose.onNodeWithText("Answer sent").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Sent; the agent picks it up now.").fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("answer-field").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the transcript's card answers through the controls it is given, and stays read-only without them`() {
        val call = ToolCall("call-q", "ask_question", ToolKind.Question, ToolCall.STATUS_RUNNING, "", payload = question)
        val group = ActivityGroup("g1", listOf(call))
        val answers = mutableListOf<Pair<String, List<ToolPayload.Question.Answer>>>()
        var controls by mutableStateOf(TranscriptControls())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) {
                    TimelineItemView(group, androidx.compose.ui.Modifier)
                }
            }
        }
        // Rendered without a media context there is no chat to link to, so the read-only card is its hint alone.
        assertThat(compose.onAllNodesWithText("Answering needs Cursor's own app or Extended mode", substring = true).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithTag("send-answer").fetchSemanticsNodes()).isEmpty()

        controls = TranscriptControls(onAnswer = { id, a -> answers += id to a })
        compose.waitForIdle()
        chip("All of them").performClick()
        chip("Now").performClick()
        compose.onNodeWithTag("send-answer").performClick()
        assertThat(answers.single().first).isEqualTo("call-q")
        assertThat(answers.single().second.map { it.selectedOptionIds }).containsExactly(listOf("all"), listOf("now")).inOrder()

        // The account took it: the card reads as sent, keyed by the call.
        controls = controls.copy(state = ConversationControls(answeredCallIds = setOf("call-q")))
        compose.waitForIdle()
        compose.onNodeWithText("Answer sent").assertIsDisplayed()
    }
}
