package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The account's queue above the composer (Extended mode): one row per queued follow-up with send now, steer now
 * while a turn can be steered, remove, edit, and the reorder menu once there is an order to change — the queue where
 * Cursor's own clients keep it, not a panel section.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AccountQueueRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private val queue = listOf(
        PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS),
        PendingFollowup("fu-2", "And a changelog line under Unreleased", 2_000L, AgentSource.API, isEditing = true),
    )

    private val acted = mutableListOf<String>()
    private var steerable by mutableStateOf(true)
    private var inFlight by mutableStateOf(emptySet<String>())
    private var rows by mutableStateOf(queue)

    private fun show(reorder: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AccountQueueRows(
                    queue = rows,
                    inFlightIds = inFlight,
                    onSendNow = { acted += "now:${it.id}" },
                    onRemove = { acted += "remove:${it.id}" },
                    onUpdate = { item, text -> acted += "update:${item.id}:$text" },
                    onEditing = { item, editing -> acted += "editing:${item.id}:$editing" },
                    onSteerNow = if (steerable) ({ acted += "steer:${it.id}" }) else null,
                    onMove = if (reorder) ({ item, up -> acted += "move:${item.id}:${if (up) "up" else "down"}" }) else null,
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `each row carries send now, steer now, remove and edit, and none of them overlap`() {
        show()
        assertThat(compose.onAllNodesWithTag("account-queue-row").fetchSemanticsNodes()).hasSize(2)
        assertThat(shown("Then add a test for the light theme")).isTrue()
        assertThat(shown("Being edited on another device")).isTrue()
        compose.onAllNodesWithContentDescription("Send now")[0].performClick()
        compose.onAllNodesWithContentDescription("Steer now")[1].performClick()
        compose.onAllNodesWithContentDescription("Remove queued follow-up")[1].performClick()
        assertThat(acted).containsExactly("now:fu-1", "steer:fu-2", "remove:fu-2").inOrder()

        val glyphs = listOf("Reorder queued follow-up", "Steer now", "Remove queued follow-up", "Edit queued follow-up", "Send now")
            .map { compose.onAllNodesWithContentDescription(it)[0].fetchSemanticsNode().boundsInRoot }
        for (i in glyphs.indices) for (j in i + 1 until glyphs.size) assertThat(glyphs[i].intersects(glyphs[j])).isFalse()
    }

    @Test
    fun `steer now is only there while the turn can be steered`() {
        show()
        assertThat(compose.onAllNodesWithContentDescription("Steer now").fetchSemanticsNodes()).hasSize(2)
        steerable = false
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription("Steer now").fetchSemanticsNodes()).isEmpty()
        // Send now stays: it interrupts the turn rather than steering it.
        assertThat(compose.onAllNodesWithContentDescription("Send now").fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun `editing a row flags it on the account, saving rewords it, and cancelling hands the flag back`() {
        show()
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        assertThat(acted).contains("editing:fu-1:true")
        compose.onNodeWithTag("account-queue-edit").performTextInput(", and dark")
        compose.onAllNodesWithContentDescription("Save queued follow-up")[0].performClick()
        assertThat(acted.last()).isEqualTo("update:fu-1:Then add a test for the light theme, and dark")

        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        compose.onAllNodesWithContentDescription("Cancel editing")[0].performClick()
        assertThat(acted.last()).isEqualTo("editing:fu-1:false")
    }

    @Test
    fun `the reorder menu moves a row up or down, and only in the direction there is`() {
        show()
        compose.onAllNodesWithContentDescription("Reorder queued follow-up")[0].performClick()
        compose.onNodeWithText("Move up").assertIsNotEnabled()
        compose.onNodeWithText("Move down").assertIsEnabled().performClick()
        assertThat(acted).contains("move:fu-1:down")
        compose.onAllNodesWithContentDescription("Reorder queued follow-up")[1].performClick()
        compose.onNodeWithText("Move down").assertIsNotEnabled()
        compose.onNodeWithText("Move up").performClick()
        assertThat(acted).contains("move:fu-2:up")

        // One row has no order to change: no menu.
        rows = queue.take(1)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription("Reorder queued follow-up").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a row the account has in flight shows a ring in place of its glyphs`() {
        inFlight = setOf("fu-1")
        show(reorder = false)
        assertThat(compose.onAllNodesWithContentDescription("Sending").fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithContentDescription("Send now").fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithContentDescription("Reorder queued follow-up").fetchSemanticsNodes()).isEmpty()
    }

    private fun Rect.intersects(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
