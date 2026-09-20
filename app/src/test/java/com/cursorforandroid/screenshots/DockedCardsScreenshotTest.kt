package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.LoadErrorRow
import com.cursorforandroid.ui.conversation.QueuedFollowUps
import com.cursorforandroid.ui.conversation.RecordFallbackRow
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Everything that docks over the follow-up composer, one frame per stack: one, two and three queued follow-ups; a
 * follow-up held back by a rate limit; the account's record refused and the transcript's refresh failed, each said in
 * a card of its own; and the record's card over two queued follow-ups. The stack is laid out the way the chat lays it
 * out ([com.cursorforandroid.ui.conversation.ConversationScreen]): the composer's gutter at each side, and the strips
 * over the box with the same gap between each. Same device qualifiers as [AppScreenshotTest]; the held card's clock
 * is pinned so its "waited" line reads the same on every run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DockedCardsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private val now = 1_800_000_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val queued = listOf(
        QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L),
        QueuedFollowUp("q-2", "Attach the before and after screenshots to the PR", images = listOf(DraftImage("img-1", PromptImage(ByteArray(0), "image/png"))), queuedAtMillis = 2_000L),
        QueuedFollowUp("q-3", "And bump the version once it is green", queuedAtMillis = 3_000L),
    )

    /** Refused with a wait the server named: held, the card saying why in the server's words, tried again by itself. */
    private val rateLimited = QueuedFollowUp(
        "q-4", "And bump the version once it is green", queuedAtMillis = 3_000L,
        heldSinceMillis = now - 12_000L, notBeforeMillis = now + 18_000L,
        holdReason = QueuedFollowUp.RATE_LIMITED, serverReason = "Too many requests from this key.", throttleRefusals = 1,
    )

    private val fallback = RecordFallback("FetchBackgroundComposer has been removed", sinceMillis = now, readMillis = 640L, retryAfterMillis = null)

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /**
     * The bottom of the chat as the screen composes it — the dock: the gutter at each side, the gap under the box,
     * and over the box the load notice first, then the strips, each card the same distance from the next.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Dock(notice: (@Composable () -> Unit)? = null, strips: @Composable ColumnScope.() -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Column(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(top = 16.dp).testTag("scene")) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        notice?.invoke()
                        strips()
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (sends when the turn ends)…",
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

    @Composable
    private fun Queue(items: List<QueuedFollowUp>) {
        QueuedFollowUps(
            queue = items,
            thumbnails = emptyMap(),
            onEdit = {},
            onSteer = {},
            onRemove = {},
            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
        )
    }

    private val noticeModifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp)

    @Test
    fun oneQueuedCard() {
        compose.setContent { Dock { Queue(queued.take(1)) } }
        capture("101_dock_one_queued_card")
    }

    @Test
    fun twoQueuedCards() {
        compose.setContent { Dock { Queue(queued.take(2)) } }
        capture("102_dock_two_queued_cards")
    }

    @Test
    fun threeQueuedCards() {
        compose.setContent { Dock { Queue(queued) } }
        capture("103_dock_three_queued_cards")
    }

    @Test
    fun rateLimitedCard() {
        compose.setContent { Dock { Queue(listOf(rateLimited)) } }
        capture("104_dock_rate_limited_card")
    }

    @Test
    fun recordFallbackCard() {
        compose.setContent {
            Dock(notice = { RecordFallbackRow(fallback, onRetry = {}, onShareDiagnostics = {}, modifier = noticeModifier) }) {}
        }
        capture("105_dock_record_fallback_card")
    }

    @Test
    fun recordFallbackCardOverTheQueue() {
        compose.setContent {
            Dock(notice = { RecordFallbackRow(fallback, onRetry = {}, onShareDiagnostics = {}, modifier = noticeModifier) }) { Queue(queued.take(2)) }
        }
        capture("106_dock_record_fallback_over_queue")
    }

    @Test
    fun loadErrorCard() {
        compose.setContent {
            Dock(notice = { LoadErrorRow("Couldn't refresh the transcript: Cursor took too long to respond.", onRetry = {}, onShareDiagnostics = {}, modifier = noticeModifier) }) {}
        }
        capture("107_dock_load_error_card")
    }
}
