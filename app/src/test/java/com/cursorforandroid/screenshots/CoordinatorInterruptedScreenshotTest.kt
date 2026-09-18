package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.ShapeFixtures
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Bennett's coordinator chat as v0.3.25 showed it (internal/reference/coordinator-0.3.25-no-replies-cancelled.jpg):
 * his messages with the coordinator's replies missing between them, and two "Run cancelled" warning rows for the
 * runs his next messages cut short. Here the same chat as this build reads it: the coordinator's `SendMessage`
 * whose streamed pieces the record cut short (the shape-dump fixture, `shape_dump_0325.txt`) shown from those
 * pieces with one dimmed line saying it was recovered; each run he interrupted reading "Worked … · interrupted" at
 * the end of its stretch's line, with no warning row — not from the trace, and not from the notice an earlier
 * build left on disk. Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorInterruptedScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }
    private val t0 = 1_789_600_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { t0 + 90 * 60_000L }
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

    /** The record's first turn of the shape-dump fixture: the update streamed in pieces the record cut short. */
    private fun recoveredTurn(): List<TimelineItem> {
        val dump = CoordinatorFixtures.text("shape_dump_0325.txt")
        val pieces = listOf(
            """{"text":{"content":"The phone worker has the Fold8""",
            """ and Fold8 Ultra on its list and is rendering""",
            """ app-demo shots on every device; first frames in""",
        )
        fun values(line: Int, path: String): JsonElement? {
            val index = ShapeFixtures.lines(dump)[line].index
            val value = when (path) {
                "humanMessage.text" -> "So where we at rn"
                // The narration's step only: the message-done marker's empty text stays empty.
                "text" -> "Bennett wants the phone renders checked; the phone worker has the Fold8 pair on its list.".takeIf { index == 4022 }
                "toolCall.toolCallId", "finalToolResult.toolCallId" -> "toolu_01SendPieces"
                "toolCall.rawArgs" -> pieces.getOrNull(index - 4023)
                else -> null
            }
            return value?.let { JsonPrimitive(it) }
        }
        val turn = HeadlessTranscript.split(ShapeFixtures.steps(dump, ::values)).first()
        return HeadlessTranscript.body(turn, "rec-4021")
    }

    private fun event(index: Int, id: String, at: Long): List<TimelineItem> =
        SystemNotifications.parse(id, wall[index].getValue("text").jsonPrimitive.content, at)!!.items.map { (it as SystemNotification).copy(timestampMillis = at) }

    /** Silent turns between two of Bennett's messages: [count] injected turns a minute apart from [from], each closed by its footer. */
    private fun silent(prefix: String, from: Long, count: Int): List<TimelineItem> = (0 until count).flatMap { i ->
        event(i % wall.size, "$prefix-$i", from + i * 60_000L) + RunFooter("$prefix-footer-$i", "run-$prefix-$i", RunStatus.FINISHED, 12_000, emptyList(), endedAtMillis = from + i * 60_000L + 12_000)
    }

    /**
     * The chat: Bennett's message and the recovered update; three silent turns; his next message, the coordinator
     * working on it until the message after that cancelled the run (a warning notice an earlier build wrote for it,
     * still in the trace); the same again; his last message and a finished run of silent turns.
     */
    private fun items(): List<TimelineItem> = buildList {
        add(UserMessage("u1", "So where we at rn", t0))
        addAll(recoveredTurn())
        add(RunFooter("rec-footer-4021", "run-4021", RunStatus.FINISHED, 14_000, emptyList(), endedAtMillis = t0 + 14_000))
        addAll(silent("a", t0 + 60_000L, 3))
        add(UserMessage("u2", "So the shot/image-only stuff could use the phones for demoing apps. Have that phone agent make shots for that.", t0 + 5 * 60_000L))
        add(AssistantMessage("n-u2", "Telling the phone worker to render app-demo shots on every device."))
        // The trace as a build before this one wrote it: the notice ahead of the footer, in the order finish() added them.
        add(NoticeCard("legacy-u2", NoticeCard.RUN_CANCELLED, null, NoticeTone.Warning))
        add(RunFooter("f-u2", "run-u2", RunStatus.CANCELLED, 694_000, emptyList(), endedAtMillis = t0 + 17 * 60_000L))
        add(UserMessage("u3", "I'm in it for all products and renders, by the way. Nothing looks realistic enough yet. Keep on iterating until they are indiscernible from the human eye.", t0 + 17 * 60_000L + 4_000))
        addAll(silent("b", t0 + 18 * 60_000L, 2))
        add(RunFooter("f-u3", "run-u3", RunStatus.CANCELLED, 167_000, emptyList(), endedAtMillis = t0 + 23 * 60_000L))
        add(UserMessage("u4", "Also make the Z Fold8 and Z Fold8 Ultra devices alongside the rest.", t0 + 23 * 60_000L + 2_000))
        addAll(silent("c", t0 + 24 * 60_000L, 4))
    }

    private fun rows(): List<TranscriptRow> = TranscriptRows.of(CoordinatorTranscript.present(items(), coordinatorMode = true), coordinatorMode = true)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, coordinatorMode = true)) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun coordinatorInterrupted() {
        val rows = rows()
        // The recovered update is the message; no warning row anywhere; the interrupted runs say so at the end of their lines.
        val message = rows.filterIsInstance<TranscriptRow.Message>().single()
        assertThat((message.call.payload as ToolPayload.CoordinatorMessage).recovered).isTrue()
        assertThat(rows.none { it is TranscriptRow.Item && it.item is NoticeCard }).isTrue()
        val summaries = rows.filterIsInstance<TranscriptRow.Stretch>().filter { it.single == null }.map { it.summary.text }
        assertThat(summaries).containsExactly(
            "1 note",
            "Worked 50s · 3 events",
            "Worked 11m 34s · 1 note · interrupted",
            "Worked 3m 11s · 2 events · interrupted",
            "Worked 48s · 4 events",
        ).inOrder()
        show(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Worked 11m 34s").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("coordinator-message-recovered-note").assertIsDisplayed()
        compose.onAllNodesWithText(NoticeCard.RUN_CANCELLED).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("stretch")).assertCountEquals(5)
        capture("76_coordinator_interrupted")
    }
}
