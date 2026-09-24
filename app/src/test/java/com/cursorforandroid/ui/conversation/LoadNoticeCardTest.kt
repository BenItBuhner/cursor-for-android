package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.RecordFallback
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The X on a load notice's card (Bennett's 2026-09-20 frame: a way to close the notice, in the top-right): where it
 * stands, what it takes touches over, what tapping it does — and which cards have none: the failure that is the
 * whole screen, and the queued and held follow-ups, which are not notices.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class LoadNoticeCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val fallback = RecordFallback("FetchBackgroundComposer has been removed", sinceMillis = 1_000L, readMillis = 640L, retryAfterMillis = null)

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the X stands in the top-right corner, clear of a title that wraps, on a finger's touch target, and closes the card`() {
        var closed = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // A phone's width less the dock's gutter: the title runs to two lines beside the X, as in Bennett's frame.
                Column(Modifier.width(411.dp).padding(horizontal = CursorDimens.composerGutter)) {
                    RecordFallbackRow(fallback, onRetry = {}, onShareDiagnostics = {}, onDismiss = { closed++ }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        // The card's surface, inside the dock's inset (the outer "record-fallback" box spans the inset too).
        val card = bounds("load-notice")
        val disc = bounds("load-notice-dismiss")
        val title = bounds("load-notice-title")
        val hit = compose.onNodeWithContentDescription("Close notice").fetchSemanticsNode().boundsInRoot
        // The title wrapped, and ends before the X begins: nothing of it runs under the disc.
        assertThat(title.height / density).isGreaterThan(20f)
        assertThat(title.right).isAtMost(disc.left)
        // The composer's glyph size, touches taken over the full 44dp — the whole target inside the card, since the
        // card's clipped surface is what takes the touch (the bounds read here are clipped the same way).
        assertThat(disc.width / density).isWithin(0.5f).of(24f)
        assertThat(disc.height / density).isWithin(0.5f).of(24f)
        assertThat(hit.width / density).isWithin(0.5f).of(CursorDimens.touchTarget.value)
        assertThat(hit.height / density).isWithin(0.5f).of(CursorDimens.touchTarget.value)
        assertThat(hit.right).isAtMost(card.right + 0.5f)
        assertThat(hit.top).isAtLeast(card.top - 0.5f)
        // In the corner: the disc's centre half a target in from the card's top and end.
        assertThat((card.right - (disc.left + disc.width / 2)) / density).isWithin(0.5f).of(CursorDimens.touchTarget.value / 2)
        assertThat(((disc.top + disc.height / 2) - card.top) / density).isWithin(0.5f).of(CursorDimens.touchTarget.value / 2)
        // And centred on the title's first line.
        assertThat((disc.top + disc.height / 2) / density).isWithin(0.5f).of((title.top + 18 * density / 2) / density)
        compose.onNodeWithTag("load-notice-dismiss").performClick()
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `a card given no way to close has no X, and neither has a queued or a held follow-up`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.width(411.dp)) {
                    // The failure that is the whole screen: closing it would leave a blank transcript with nothing to act on.
                    LoadErrorRow("Couldn't load the transcript: Cursor took too long to respond.", onRetry = {}, onShareDiagnostics = {}, docked = false, modifier = Modifier.fillMaxWidth())
                    QueuedFollowUps(
                        queue = listOf(
                            QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L),
                            QueuedFollowUp("q-2", "And bump the version", queuedAtMillis = 2_000L, heldSinceMillis = 1_000L, notBeforeMillis = 30_000L, holdReason = QueuedFollowUp.RATE_LIMITED, serverReason = "Too many requests.", throttleRefusals = 1),
                        ),
                        thumbnails = emptyMap(),
                        onEdit = {},
                        onSteer = {},
                        onRemove = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("load-error").assertExists()
        compose.onAllNodesWithTag("load-notice-dismiss").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Close notice").assertCountEquals(0)
        // The follow-ups keep their own three glyphs and nothing that closes them.
        compose.onAllNodesWithContentDescription("Remove queued follow-up").assertCountEquals(2)
    }
}
