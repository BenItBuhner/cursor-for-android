package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.repo.PromptlessFixture
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.data.repo.TurnPairing
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The newest turns of a Project in Bennett's Polymarket shape (see [PromptlessFixture]: 324 runs, 26 of them no
 * prompt started, 298 prompts, the record refused), twice:
 *
 *  - before: the transcript laid over the runs by position, as 0.3.56 did — the frame of 2026-09-21 09:17: the
 *    coordinator's replies one after another, each with its stretch, and not one of Bennett's prompts between them,
 *    the prompts that started those turns standing twenty-six turns up;
 *  - after: the runs attached to the prompts by evidence ([TurnPairing]) — every prompt in the transcript's order,
 *    directly above the turn it started, the live one above the live stretch.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PromptOrderScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val firstAt = 1_800_000_000_000L - PromptlessFixture.TURNS * LongProject.TURN_SPACING_MS
    private val turns = PromptlessFixture.turns(firstAt)
    private val messages = LongProject.v0Transcript(turns)
    private val runs = turns.map { t -> RunDto(t.runId, LongProject.AGENT_ID, t.status, LongProject.iso(t.startedAt), LongProject.iso(t.endedAt), t.durationMs, result = LongProject.result(t)) }
    /** Each run's activity as its log replays: the same accumulator the stream's events go through. */
    private val traces: Map<String, List<TimelineItem>> = turns.associate { turn -> turn.runId to replay(turn) }

    @Before
    fun pinClock() {
        AppClock.nowMillis = { turns.last().startedAt + 90_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun replay(turn: LongProject.Turn): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun(turn.runId, timed = false)
        val source = Buffer()
        turn.log.forEach { (event, data) -> source.writeUtf8("event: $event\ndata: $data\n\n") }
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            val event = (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event ?: continue
            if (event is RunStreamEvent.Done) continue
            live.apply(event)
        }
        return live.snapshot()
    }

    /** The newest [WINDOW] turns as the screen opens on them, built and presented as the documented path builds them. */
    private fun rows(pairedTurns: List<TurnPairing.Turn>): List<TranscriptRow> {
        val items = TimelineBuilder.fromTurns(pairedTurns.takeLast(WINDOW), traces)
        return TranscriptPresenter().present(items, coordinatorMode = true, runActive = true).rows
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true)) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The user's bubbles among [rows], in order, as the prompts' words. */
    private fun bubbles(rows: List<TranscriptRow>): List<String> = rows.filterIsInstance<TranscriptRow.Item>().mapNotNull { (it.item as? UserMessage)?.text }

    private companion object {
        /** Bennett's frame held the newest four coordinator replies and their stretches: the turns behind them. */
        const val WINDOW = 12
    }

    @Test
    fun promptOrderBefore() {
        val rows = rows(TurnPairing.positional(messages, runs))
        // The frame: the replies and their stretches, and no prompt of Bennett's between them — by position the
        // newest 26 runs have none, and the window's 12 are among them.
        assertThat(bubbles(rows)).isEmpty()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>().size).isAtLeast(2)
        show { rows.takeLast(8).forEach { TranscriptRowView(it) } }
        capture("121_prompt_order_before")
    }

    @Test
    fun promptOrderAfter() {
        val rows = rows(TurnPairing.pair(messages, runs).turns)
        // Bennett's prompts back between the replies, each above the turn it started; the newest above the live stretch.
        val prompts = bubbles(rows)
        assertThat(prompts).isNotEmpty()
        assertThat(prompts.last()).isEqualTo(turns.last().prompt)
        assertThat(rows.indexOfLast { it is TranscriptRow.Item && it.item is UserMessage }).isLessThan(rows.indexOfLast { it is TranscriptRow.Stretch })
        show { rows.takeLast(8).forEach { TranscriptRowView(it) } }
        capture("122_prompt_order_after")
    }
}
