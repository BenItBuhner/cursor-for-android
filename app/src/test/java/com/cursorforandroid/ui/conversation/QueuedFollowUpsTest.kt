package com.cursorforandroid.ui.conversation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueuedFollowUpsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(item: QueuedFollowUp) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                QueuedFollowUps(
                    queue = listOf(item),
                    thumbnails = emptyMap(),
                    onEdit = {},
                    onSteer = {},
                    onRemove = {},
                )
            }
        }
    }

    @Test
    fun `the three queue actions do not overlap`() {
        show(
            QueuedFollowUp(
                id = "q1",
                text = "Follow up text",
                queuedAtMillis = 0L,
                needsConfirmation = true,
            ),
        )
        compose.waitForIdle()

        val remove = compose.onNodeWithContentDescription("Remove queued follow-up").fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithContentDescription("Edit queued follow-up").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Retry sending").fetchSemanticsNode().boundsInRoot

        assertThat(remove.intersects(edit)).isFalse()
        assertThat(edit.intersects(send)).isFalse()
        assertThat(remove.intersects(send)).isFalse()
    }

    @Test
    fun `a row that needs confirmation shows its warning`() {
        show(
            QueuedFollowUp(
                id = "q1",
                text = "Follow up text",
                queuedAtMillis = 0L,
                needsConfirmation = true,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(QueuedFollowUp.MAY_HAVE_BEEN_SENT).assertExists()
    }

    @Test
    fun `a held row reads as waiting with the time waited, steadily, whether or not an attempt is in flight`() {
        AppClock.nowMillis = { 100_000L + 83_000L }
        try {
            show(
                QueuedFollowUp(
                    id = "q1",
                    text = "Follow up text",
                    queuedAtMillis = 0L,
                    heldSinceMillis = 100_000L,
                    busyRefusals = 2,
                    isSending = true,
                ),
            )
            compose.waitForIdle()
            // One steady line — no ring, no "sending" — even though this frame has an attempt out.
            compose.onNodeWithTag("queued-held").assertExists()
            compose.onNodeWithText(QueuedFollowUp.WAITING_FOR_AGENT, substring = true).assertExists()
            compose.onNodeWithText("1m 23s", substring = true).assertExists()
            compose.onAllNodesWithContentDescription("Sending").assertCountEquals(0)
            // Not yet the server's words: two refusals so far.
            compose.onAllNodesWithTag("queued-held-reason").assertCountEquals(0)
        } finally {
            AppClock.nowMillis = System::currentTimeMillis
        }
    }

    @Test
    fun `from the third refusal the card carries the server's own reason`() {
        show(
            QueuedFollowUp(
                id = "q1",
                text = "Follow up text",
                queuedAtMillis = 0L,
                heldSinceMillis = 0L,
                busyRefusals = 3,
                serverReason = "Agent is busy.",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("queued-held-reason").assertExists()
        compose.onNodeWithText("Cursor says: Agent is busy.").assertExists()
    }

    private fun Rect.intersects(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
