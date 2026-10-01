package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentSource
import androidx.compose.foundation.layout.Column
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.ui.components.Keyboard
import com.cursorforandroid.ui.components.pressEnter
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The account's queue above the composer (Extended mode): one row per queued follow-up with remove, edit, the up
 * arrow — which steers into the turn under way while there is one, and sends now while there is not — and the
 * reorder menu once there is an order to change; no other glyph steers. The queue where Cursor's own clients keep
 * it, not a panel section.
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
    private var steers by mutableStateOf(true)
    private var inFlight by mutableStateOf(emptySet<String>())
    private var rows by mutableStateOf(queue)

    private fun show(reorder: Boolean = true) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AccountQueueRows(
                    queue = rows,
                    inFlightIds = inFlight,
                    onSteer = { acted += "arrow:${it.id}" },
                    onRemove = { acted += "remove:${it.id}" },
                    onUpdate = { item, text -> acted += "update:${item.id}:$text" },
                    onEditing = { item, editing -> acted += "editing:${item.id}:$editing" },
                    steers = steers,
                    onMove = if (reorder) ({ item, up -> acted += "move:${item.id}:${if (up) "up" else "down"}" }) else null,
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun edited(): String =
        compose.onNodeWithTag("account-queue-edit").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun `each row carries remove, edit and the up arrow that steers, and none of them overlap`() {
        show()
        assertThat(compose.onAllNodesWithTag("account-queue-row").fetchSemanticsNodes()).hasSize(2)
        assertThat(shown("Then add a test for the light theme")).isTrue()
        assertThat(shown("Being edited on another device")).isTrue()
        compose.onAllNodesWithContentDescription(QueueGlyphs.STEER)[0].performClick()
        compose.onAllNodesWithContentDescription(QueueGlyphs.STEER)[1].performClick()
        compose.onAllNodesWithContentDescription("Remove queued follow-up")[1].performClick()
        assertThat(acted).containsExactly("arrow:fu-1", "arrow:fu-2", "remove:fu-2").inOrder()

        val glyphs = listOf("Reorder queued follow-up", "Remove queued follow-up", "Edit queued follow-up", QueueGlyphs.STEER)
            .map { compose.onAllNodesWithContentDescription(it)[0].fetchSemanticsNode().boundsInRoot }
        for (i in glyphs.indices) for (j in i + 1 until glyphs.size) assertThat(glyphs[i].intersects(glyphs[j])).isFalse()
    }

    @Test
    fun `the target glyph is gone - the up arrow is the steer while a turn runs, and send now while none does`() {
        show()
        // Four glyphs a row, as before the steer moved onto the arrow less one: reorder, remove, edit, the arrow.
        assertThat(compose.onAllNodesWithContentDescription("Steer now").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithContentDescription(QueueGlyphs.STEER).fetchSemanticsNodes()).hasSize(2)
        assertThat(compose.onAllNodesWithContentDescription(QueueGlyphs.SEND).fetchSemanticsNodes()).isEmpty()
        steers = false
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription(QueueGlyphs.STEER).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithContentDescription(QueueGlyphs.SEND).fetchSemanticsNodes()).hasSize(2)
        assertThat(compose.onAllNodesWithContentDescription("Steer now").fetchSemanticsNodes()).isEmpty()
        compose.onAllNodesWithContentDescription(QueueGlyphs.SEND)[0].performClick()
        assertThat(acted).containsExactly("arrow:fu-1")
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
    fun `a physical Enter saves an edited row, as submitting one does on the desktop`() {
        show()
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        compose.onNodeWithTag("account-queue-edit").performTextInput(", and dark")
        compose.onNodeWithTag("account-queue-edit").pressEnter()
        assertThat(acted.last()).isEqualTo("update:fu-1:Then add a test for the light theme, and dark")
        assertThat(compose.onAllNodesWithTag("account-queue-edit").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `in an edit Shift+Enter and the on-screen Enter break the line, and an emptied edit is not saved from the keyboard`() {
        show()
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        val edit = compose.onNodeWithTag("account-queue-edit")
        edit.pressEnter(shift = true)
        edit.pressEnter(Keyboard.OnScreen)
        assertThat(edited()).isEqualTo("Then add a test for the light theme\n\n")
        assertThat(acted.none { it.startsWith("update:") }).isTrue()

        edit.performTextClearance()
        edit.pressEnter()
        assertThat(edited()).isEmpty()
        assertThat(acted.none { it.startsWith("update:") }).isTrue()
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
        assertThat(compose.onAllNodesWithContentDescription(QueueGlyphs.STEER).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithContentDescription("Reorder queued follow-up").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * Bennett's frame of v0.4.26: a row steered mid-turn sat with its glyphs live and "Being delivered to the agent."
     * under it. A steered row reads as a steered device card does: "Steering…" then "Steered", no ring though its
     * promote is out, its glyphs dimmed and every tap on them refused with why in place of the note — never acted on.
     */
    @Test
    fun `a steered row says steering then steered, its glyphs dimmed, and refuses every tap in place`() {
        val refusals = mutableListOf<String>()
        var refusedId by mutableStateOf<String?>(null)
        rows = listOf(queue[0].copy(steer = SteerPhase.STEERING, note = null), queue[1].copy(isEditing = false))
        inFlight = setOf("fu-1")
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    rows.forEachIndexed { index, item ->
                        AccountQueueCard(
                            item = item,
                            position = index + 1,
                            count = rows.size,
                            inFlightIds = inFlight,
                            onSteer = { acted += "arrow:${it.id}" },
                            onRemove = { acted += "remove:${it.id}" },
                            onUpdate = { row, text -> acted += "update:${row.id}:$text" },
                            onEditing = { row, editing -> acted += "editing:${row.id}:$editing" },
                            steers = true,
                            onMove = { row, up -> acted += "move:${row.id}:$up" },
                            flights = null,
                            refused = refusedId == item.id,
                            onRefused = { refusals += it.id; refusedId = it.id },
                        )
                    }
                }
            }
        }
        // Steering: the note, no ring, the glyphs on their way — the other row's untouched.
        compose.onNodeWithText(QueueCardWords.STEERING).assertExists()
        assertThat(compose.onAllNodesWithContentDescription("Sending").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag(QueueGlyphs.ON_ITS_WAY_TAG).fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithContentDescription("Queued on your account, 1 of 2, steering into this turn").fetchSemanticsNodes()).hasSize(1)
        assertThat(shown(QueuePlacement.DELIVERING_NOTE)).isFalse()

        // Every glyph of the steered row is refused, and says why where the note was; nothing is done to the message.
        compose.onAllNodesWithContentDescription("Remove queued follow-up")[0].performClick()
        compose.onAllNodesWithContentDescription("Edit queued follow-up")[0].performClick()
        compose.onAllNodesWithContentDescription(QueueGlyphs.STEER)[0].performClick()
        compose.onAllNodesWithContentDescription("Reorder queued follow-up")[0].performClick()
        assertThat(refusals).containsExactly("fu-1", "fu-1", "fu-1", "fu-1")
        assertThat(acted).isEmpty()
        assertThat(compose.onAllNodesWithText("Move up").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("account-queue-edit").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(QueueGlyphs.REFUSED_TAG).assertExists()
        assertThat(shown(QueueGlyphs.REFUSED)).isTrue()

        // The account took it: "Steered", in place; the other row's glyphs still act.
        refusedId = null
        rows = listOf(rows[0].copy(steer = SteerPhase.STEERED), rows[1])
        inFlight = emptySet()
        compose.waitForIdle()
        compose.onNodeWithText(QueueCardWords.STEERED).assertExists()
        assertThat(compose.onAllNodesWithTag(QueueGlyphs.ON_ITS_WAY_TAG).fetchSemanticsNodes()).hasSize(1)
        compose.onAllNodesWithContentDescription("Remove queued follow-up")[1].performClick()
        assertThat(acted).containsExactly("remove:fu-2")
    }

    private fun Rect.intersects(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
