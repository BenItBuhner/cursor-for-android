package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.DockInset
import com.cursorforandroid.ui.components.composerDockInset
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorShapes
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cards docked over the follow-up composer — a load notice, the record's notice, the goal strip, a queued
 * follow-up, the account's queue row — and where their edges fall against the box's. What is held: each card's flat
 * top and bottom edges begin and end where the composer's flat edges do, so their corner arcs are concentric with the
 * box's rather than pinching in before it; the inset that does it is the difference between the two radii, read from
 * the tokens; and the stack keeps one gap between every pair of cards, whatever the mix.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DockedCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val t0 = 1_789_380_000_000L

    /** Every kind of card the chat docks, stacked as the screen stacks them, over the box. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showStack() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.fillMaxWidth().padding(CursorDimens.composerGutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadErrorRow("Couldn't refresh the transcript: Cursor took too long to respond.", onRetry = {}, onShareDiagnostics = {}, onDismiss = {}, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    RecordFallbackRow(RecordFallback("Rate limited by Cursor", sinceMillis = t0, readMillis = 640L, retryAfterMillis = null), onRetry = {}, onShareDiagnostics = {}, onDismiss = {}, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    GoalStrip(goal = Goal("Ship the light theme", GoalStatus.ACTIVE, accruingSinceMillis = t0), clock = { t0 + 1_000L }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    QueuedFollowUps(
                        queue = listOf(QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L)),
                        thumbnails = emptyMap(),
                        onEdit = {},
                        onSteer = {},
                        onRemove = {},
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                    AccountQueueRows(
                        queue = listOf(PendingFollowup("fu-1", "And a changelog line under Unreleased", 1_000L, AgentSource.GLASS)),
                        inFlightIds = emptySet(),
                        onSendNow = {},
                        onRemove = {},
                        onUpdate = { _, _ -> },
                        onEditing = { _, _ -> },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                    ComposerBox(
                        value = "",
                        onValueChange = {},
                        placeholder = "Follow up…",
                        onSend = {},
                        isRunning = true,
                        onStop = {},
                        plusMenu = ComposerMenuActions(onPickMedia = {}),
                        modelLabel = "Claude Fable 5.1",
                        onModel = {},
                        modifier = Modifier.fillMaxWidth().testTag("composer"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The surfaces of the docked cards, as drawn (each tag or description sits on the card's surface), top to bottom. */
    private fun cards(): List<Pair<String, Rect>> {
        val notices = compose.onAllNodesWithTag("load-notice").fetchSemanticsNodes().map { "load notice" to it.boundsInRoot }
        return (
            notices +
                ("goal strip" to compose.onNodeWithTag("goal-strip").fetchSemanticsNode().boundsInRoot) +
                ("queued follow-up" to compose.onNodeWithContentDescription("Queued follow-up 1 of 1").fetchSemanticsNode().boundsInRoot) +
                ("account queue row" to compose.onNodeWithTag("account-queue-row").fetchSemanticsNode().boundsInRoot)
            ).sortedBy { it.second.top }
    }

    @Test
    fun `every docked card's flat edges begin and end where the composer's do, so their corners are concentric`() {
        showStack()
        val density = compose.density
        val composer = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        val composerRadius = with(density) { CursorDimens.composerRadius.toPx() }
        // The cards' corners: the theme's xl shape, resolved as the modifier resolves it.
        val cardRadius = CursorShapes().xl.bottomStart.toPx(Size(200f, 200f), density)
        assertThat(cardRadius).isLessThan(composerRadius)
        val cards = cards()
        assertThat(cards).hasSize(5)
        for ((name, card) in cards) {
            // Where the flat edge begins: the arc's end, one radius in from the side. The same x for card and box.
            assertWithMessage("$name: start of the flat edge").that(card.left + cardRadius).isWithin(1f).of(composer.left + composerRadius)
            assertWithMessage("$name: end of the flat edge").that(card.right - cardRadius).isWithin(1f).of(composer.right - composerRadius)
            // Which is to say the card stands the difference of the radii in from the box's sides, never flush.
            assertWithMessage("$name: inset").that(card.left - composer.left).isWithin(1f).of(composerRadius - cardRadius)
            assertWithMessage("$name: inset").that(composer.right - card.right).isWithin(1f).of(composerRadius - cardRadius)
        }
    }

    @Test
    fun `the stack keeps one gap between every card and the box, whatever the mix`() {
        showStack()
        val gap = with(compose.density) { 4.dp.toPx() }
        val edges = cards().map { it.second } + compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        edges.zipWithNext().forEachIndexed { i, (above, below) ->
            assertWithMessage("gap under card $i").that(below.top - above.bottom).isWithin(1f).of(gap)
        }
    }

    @Test
    fun `the inset is the composer's radius less the card's, read from the tokens, and never negative`() {
        var xl: DockInset? = null
        var flush: DockInset? = null
        var rounder: DockInset? = null
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                xl = composerDockInset(CursorTheme.shapes.xl)
                flush = composerDockInset(RoundedCornerShape(CursorDimens.composerRadius))
                rounder = composerDockInset(RoundedCornerShape(CursorDimens.composerRadius + 8.dp))
            }
        }
        compose.waitForIdle()
        // The current tokens: the composer's 24dp (its footer disc's 12dp plus its 12dp padding) less xl's 12dp.
        assertThat(CursorDimens.composerRadius).isEqualTo(24.dp)
        assertThat(xl).isEqualTo(DockInset(12.dp, 12.dp))
        // A card as round as the box, or rounder, stands flush rather than hanging past its sides.
        assertThat(flush).isEqualTo(DockInset(0.dp, 0.dp))
        assertThat(rounder).isEqualTo(DockInset(0.dp, 0.dp))
    }
}
