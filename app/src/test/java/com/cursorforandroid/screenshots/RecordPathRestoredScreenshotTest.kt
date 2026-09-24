package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.BlobRecord
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BlobFixtures
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.RecordFallbackRow
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The same Project chat twice, on the newest turns of the 240-turn fixture (see [LongProject]):
 *
 *  - before: what Bennett's phone showed on 2026-09-20 once Cursor had removed `FetchBackgroundComposer` — the
 *    documented path standing in: his prompts and the coordinator's notes from `/v0`, the turns whose logs the
 *    server had let go named as expired, the coordinator's messages to him gone with those logs, and over the
 *    composer the record's refusal in the server's words;
 *  - after: the record path restored on the blob-backed read Cursor's own client makes
 *    (`GetLatestAgentConversationState` + `GetBlobForAgentKV`, see `BlobRecord`): every turn whole from its blobs,
 *    the coordinator's messages among the rows, no refusal to show.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RecordPathRestoredScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val firstAt = 1_800_000_000_000L - LongProject.TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt)
    /** The newest turns the frame is cut to: from the last of Bennett's own turns (every sixteenth is his) to the live one. */
    private val newest = turns.drop(turns.indexOfLast { it.isUser })

    @Before
    fun pinClock() {
        AppClock.nowMillis = { turns.last().startedAt + 90_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun run(turn: LongProject.Turn): RunDto =
        RunDto(id = turn.runId, agentId = LongProject.AGENT_ID, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)

    /**
     * The documented fallback: `/v0`'s prompts and notes paired with the runs, the finished turns' logs expired —
     * their activity, the coordinator's messages with it, gone — and the row that says so under each.
     */
    private fun rowsBefore(): List<TranscriptRow> {
        val transcript = LongProject.v0Transcript(newest).map { V0ConversationMessageDto(it.id, it.type, it.text) }
        val runs = newest.map(::run)
        val expired = newest.filter { it.durationMs != null }.mapTo(HashSet()) { it.runId }
        val items = TimelineBuilder.fromHistory(transcript, runs, expired = expired, expiredRowWithReplies = true)
        return TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    /** The record restored: each turn read from its blobs as the account serves them, cut and built as the record path builds it. */
    private fun rowsAfter(): List<TranscriptRow> = runBlocking {
        val record = BlobFixtures.record(turns.flatMap { it.record })
        val from = turns.size - newest.size
        val steps = ArrayList<HeadlessStep>()
        for (index in from until turns.size) steps += BlobRecord.turn(index, record.turnIds[index], read = { id -> record.blobs.getValue(id) })
        val cut = HeadlessTranscript.split(steps)
        val items = ArrayList<TimelineItem>()
        cut.forEachIndexed { i, turn ->
            val source = newest[i]
            turn.prompt?.let { prompt -> items += SystemNotifications.parse("rec-prompt-$i", prompt, source.startedAt)?.items ?: listOf(UserMessage("rec-prompt-$i", prompt, source.startedAt)) }
            items += HeadlessTranscript.body(turn, "rec-$i")
            source.durationMs?.let { items += RunFooter("rec-footer-$i", source.runId, RunStatus.FINISHED, it, emptyList()) }
        }
        TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(fallback: RecordFallback?, content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) { content() }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (fallback != null) RecordFallbackRow(fallback, onRetry = {}, onShareDiagnostics = {}, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp))
                            ComposerBox(
                                value = "",
                                onValueChange = {},
                                placeholder = "Follow up (queues on your account)…",
                                onSend = {},
                                isRunning = true,
                                onStop = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}),
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun recordPathBefore() {
        val rows = rowsBefore()
        // No coordinator message survives the logs' expiry on the documented path; the expired rows say why.
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).isEmpty()
        // Bennett's phone, 2026-09-21: the server's words, and under them what was asked, byte for byte.
        val fallback = RecordFallback("getLatestAgentConversationState has been removed", sinceMillis = AppClock.now(), readMillis = 133L, retryAfterMillis = null, path = "/aiserver.v1.BackgroundComposerService/GetLatestAgentConversationState", httpCode = 404, code = "unimplemented")
        show(fallback) { rows.takeLast(7).forEach { TranscriptRowView(it) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(TimelineBuilder.EXPIRED_TITLE).fetchSemanticsNodes().isNotEmpty() }
        capture("108_record_path_before")
    }

    @Test
    fun recordPathAfter() {
        val rows = rowsAfter()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).isNotEmpty()
        // The newest rows: the reports and the stretches between them, the workers queued in each among its steps.
        assertThat(rows.takeLast(6).filterIsInstance<TranscriptRow.Stretch>().sumOf { it.subagents.size }).isAtLeast(2)
        show(fallback = null) { rows.takeLast(6).forEach { TranscriptRowView(it) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("stretch")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("subagent-row")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("record-fallback")).assertCountEquals(0)
        capture("109_record_path_after")
    }
}
