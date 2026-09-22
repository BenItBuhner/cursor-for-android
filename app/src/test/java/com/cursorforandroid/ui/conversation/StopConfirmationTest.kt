package com.cursorforandroid.ui.conversation

import android.content.Context
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.RunInterruption
import com.cursorforandroid.ui.components.RunStopCopy
import com.cursorforandroid.ui.components.RunStopTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Instant

/**
 * Settings › Confirm before stopping, on the chat a palm can stop by accident: a run held open by the fakes (served
 * through the demo seat of the graph, so the whole pipeline runs — repository, view model, screen), and the Stop
 * that sits on the composer. With the setting on — the default — every way of stopping asks first and only the
 * dialog's Stop cancels the run; with it off, Stop cancels at once, as it always did.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StopConfirmationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val agentId = "bc-stop-confirm"
    private val runId = "run-stop-confirm-1"
    private val startedAt = "2026-04-13T18:30:00.000Z"
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { Instant.parse(startedAt).toEpochMilli() + 90_000L }
        api.addRunningAgent(agentId, "Fix the login redirect loop", runId, createdAt = startedAt)
        api.transcripts[agentId] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Fix the login redirect loop on the web app"))
        runBlocking {
            // Never Done: the run stays under way until something cancels it.
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Reading the auth middleware before touching the redirect."))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, streamer, isDemo = true),
        )
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun openChat() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) { composerStopShown() && graph.conversations.state(agentId).value.runStatus == RunStatus.RUNNING }
    }

    private fun composerStopShown() = compose.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isNotEmpty()

    private fun dialogShown() = compose.onAllNodes(hasTestTag(RunStopTags.DIALOG)).fetchSemanticsNodes().isNotEmpty()

    private fun tapComposerStop() {
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitUntil(10_000) { dialogShown() }
    }

    private fun awaitDialogGone() {
        compose.waitUntil(10_000) { !dialogShown() }
        compose.waitForIdle()
    }

    private fun assertStillRunning() {
        assertThat(api.cancelled).isEmpty()
        assertThat(graph.conversations.state(agentId).value.runStatus).isEqualTo(RunStatus.RUNNING)
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
    }

    private fun awaitCancelled() {
        compose.waitUntil(10_000) { runId in api.cancelled && graph.conversations.state(agentId).value.runStatus == RunStatus.CANCELLED }
    }

    @Test
    fun `on by default, the composer's Stop asks first - Keep running, back and a tap outside leave the run going, and Stop stops it`() {
        openChat()

        tapComposerStop()
        compose.onNodeWithText(RunInterruption.Stop.title).assertIsDisplayed()
        compose.onNodeWithText(RunInterruption.Stop.detail).assertIsDisplayed()
        compose.onNode(hasTestTag(RunStopTags.KEEP_RUNNING) and hasText(RunStopCopy.KEEP_RUNNING)).assertIsDisplayed()
        compose.onNode(hasTestTag(RunStopTags.CONFIRM) and hasText(RunInterruption.Stop.confirm)).assertIsDisplayed()
        // Nothing has gone to the server while the question stands.
        assertThat(api.cancelled).isEmpty()

        compose.onNodeWithTag(RunStopTags.KEEP_RUNNING).performClick()
        awaitDialogGone()
        assertStillRunning()

        // Keep running is the dismiss action: the back gesture keeps the run going too.
        tapComposerStop()
        Espresso.pressBack()
        awaitDialogGone()
        assertStillRunning()

        // And so does a tap outside the dialog.
        tapComposerStop()
        compose.runOnUiThread {
            val outside = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
            ShadowDialog.getLatestDialog().onTouchEvent(outside)
            outside.recycle()
        }
        awaitDialogGone()
        assertStillRunning()

        // Only the dialog's Stop cancels the run, once.
        tapComposerStop()
        compose.onNodeWithTag(RunStopTags.CONFIRM).performClick()
        awaitCancelled()
        awaitDialogGone()
        assertThat(api.cancelled).containsExactly(runId)
        // The server ends the run's stream once it has taken the cancel, and the composer's Stop goes with it.
        runBlocking {
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.CANCELLED, null, null, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        compose.waitUntil(10_000) { !composerStopShown() }
    }

    @Test
    fun `turned off, the composer's Stop stops the run at once`() {
        runBlocking { graph.prefs.setConfirmStop(false) }
        openChat()

        compose.onNodeWithContentDescription("Stop").performClick()
        awaitCancelled()
        compose.waitForIdle()
        assertThat(dialogShown()).isFalse()
        assertThat(api.cancelled).containsExactly(runId)
    }

    @Test
    fun `the chat menu's Stop asks the same question`() {
        openChat()

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Stop") and !hasTestTag(RunStopTags.CONFIRM)).performClick()
        compose.waitUntil(10_000) { dialogShown() }
        compose.onNodeWithText(RunInterruption.Stop.title).assertIsDisplayed()
        compose.onNodeWithTag(RunStopTags.KEEP_RUNNING).performClick()
        awaitDialogGone()
        assertStillRunning()

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Stop") and !hasTestTag(RunStopTags.CONFIRM)).performClick()
        compose.waitUntil(10_000) { dialogShown() }
        compose.onNodeWithTag(RunStopTags.CONFIRM).performClick()
        awaitCancelled()
        awaitDialogGone()
    }

    @Test
    fun `a question still open when the run finishes by itself goes with the run`() {
        openChat()
        tapComposerStop()

        api.runs[runId] = api.runs.getValue(runId).copy(status = "FINISHED", durationMs = 60_000L, result = "Fixed.")
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "IDLE")
        runBlocking {
            streamer.emit(runId, RunStreamEvent.Assistant("Fixed: the middleware keeps the original path now."))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Fixed.", 60_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        // Nothing is left to stop, and a Stop answered now would land on whatever turn came next.
        compose.waitUntil(10_000) { !dialogShown() }
        assertThat(api.cancelled).isEmpty()
    }

    @Test
    fun `Send now on a queued message cancels the turn under way, so it asks first too`() {
        openChat()

        // Written while the agent works, the follow-up waits on this device for the turn to end.
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Also cover the logout redirect")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Send now")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Send now").performClick()
        compose.waitUntil(10_000) { dialogShown() }
        compose.onNodeWithText(RunInterruption.SendNow.title).assertIsDisplayed()
        compose.onNode(hasTestTag(RunStopTags.CONFIRM) and hasText(RunInterruption.SendNow.confirm)).assertIsDisplayed()
        compose.onNodeWithTag(RunStopTags.KEEP_RUNNING).performClick()
        awaitDialogGone()
        // The message keeps its place in the queue and the turn carries on.
        compose.onNodeWithContentDescription("Send now").assertIsDisplayed()
        assertThat(api.runRequests).isEmpty()
        assertStillRunning()

        compose.onNodeWithContentDescription("Send now").performClick()
        compose.waitUntil(10_000) { dialogShown() }
        compose.onNodeWithTag(RunStopTags.CONFIRM).performClick()
        compose.waitUntil(10_000) { runId in api.cancelled }
        compose.waitUntil(10_000) { api.runRequests.any { it.prompt.text == "Also cover the logout redirect" } }
        awaitDialogGone()
    }
}
