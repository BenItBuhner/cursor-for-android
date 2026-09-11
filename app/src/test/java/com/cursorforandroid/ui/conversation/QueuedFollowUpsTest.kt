package com.cursorforandroid.ui.conversation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
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

    private fun Rect.intersects(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
