package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A queued card through a steer: what it says at each step, and how it leaves the stack. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SteerInQueueTest {

    @get:Rule
    val compose = createComposeRule()

    private val waiting = QueuedFollowUp("q-1", "Also check the release build", queuedAtMillis = 1_000L)
    private val next = QueuedFollowUp("q-2", "Then open a draft PR", queuedAtMillis = 2_000L)

    private var queue by mutableStateOf(listOf<QueuedFollowUp>())
    private val steered = mutableListOf<String>()

    private fun stack() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    QueueStack(keys = queue.map { it.id }, stacked = false, onStackedChange = {}, animate = { true }, gapBelow = 4.dp) { index, face ->
                        QueuedFollowUpCard(queue[index], index + 1, queue.size, emptyMap(), {}, { steered += it.id }, {}, flights = null, face = face, steers = true)
                    }
                }
            }
        }
    }

    /** The clock is stopped (a ring never idles): what was just set is taken up, then [millis] of it run. */
    private fun advance(millis: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun stackHeight(): Float = compose.onNodeWithTag(QueueStackTag).fetchSemanticsNode().boundsInRoot.height

    @Test
    fun `a card being steered is being sent like a held retry - glyphs dimmed - and says so under its line, then says it was steered`() {
        queue = listOf(waiting.copy(isSending = true, steer = SteerPhase.STEERING))
        stack()
        compose.mainClock.autoAdvance = false
        advance(500)
        compose.onNodeWithText(QueueCardWords.STEERING).assertExists()
        compose.onNodeWithTag(QueueGlyphs.ON_ITS_WAY_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, QueueGlyphs.ON_ITS_WAY))
        compose.onAllNodesWithContentDescription("Sending").assertCountEquals(0)
        compose.onNodeWithContentDescription("Queued follow-up 1 of 1, steering into this turn").assertExists()

        queue = listOf(waiting.copy(isSending = true, steer = SteerPhase.STEERED, steerFollowupId = "fu-1"))
        advance(500)
        compose.onNodeWithText(QueueCardWords.STEERED).assertExists()
        compose.onNodeWithTag(QueueGlyphs.ON_ITS_WAY_TAG).assertExists()
        compose.onAllNodesWithText(QueueCardWords.STEERING).assertCountEquals(0)
    }

    @Test
    fun `a steer that came back leaves the card waiting as before, the reason under it, and the up arrow steers again`() {
        queue = listOf(waiting.copy(steerError = "Couldn't steer: rejected."))
        stack()
        compose.onNodeWithTag(QueueSteerErrorTag).assertExists()
        compose.onNodeWithText("Couldn't steer: rejected.").assertExists()
        compose.onAllNodesWithTag(QueueGlyphs.ON_ITS_WAY_TAG).assertCountEquals(0)
        compose.onNodeWithContentDescription(QueueGlyphs.STEER).performClick()
        assertThat(steered).containsExactly("q-1")
    }

    @Test
    fun `a card that leaves the stack folds away over a moment, the stack's height following it down with no jump`() {
        queue = listOf(waiting.copy(isSending = true, steer = SteerPhase.STEERED, steerFollowupId = "fu-1"), next)
        stack()
        compose.mainClock.autoAdvance = false
        advance(1_000)
        val two = stackHeight()

        queue = listOf(next)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val heights = mutableListOf(stackHeight())
        repeat(((QueueExitMillis + 200) / 16)) {
            compose.mainClock.advanceTimeByFrame()
            heights += stackHeight()
        }
        val one = heights.last()

        assertThat(one).isLessThan(two)
        // The first frame after the card went still stands at the full height, and every frame after it steps down
        // by a small share of the card: no frame drops it all at once.
        assertThat(heights.first()).isWithin(2f).of(two)
        val steps = (listOf(two) + heights).zipWithNext { a, b -> a - b }
        assertThat(steps.all { it >= -0.5f }).isTrue()
        assertThat(steps.max()).isLessThan((two - one) / 2f)
        compose.onNodeWithText(next.text).assertExists()
    }

    @Test
    fun `a steered card stands until the transcript files its message, and the account's row for it is left to the card`() {
        val steeredCard = waiting.copy(isSending = true, steer = SteerPhase.STEERED, steerFollowupId = "fu-1")
        val row = PendingFollowup("fu-1", waiting.text, 1_000L, AgentSource.API)
        val other = PendingFollowup("fu-9", "Queued from the desktop", 3_000L, AgentSource.GLASS)

        val stillWaiting = QueuePlacement(waiting = listOf(row))
        assertThat(SteeredCards.standing(listOf(steeredCard, next), stillWaiting)).containsExactly(steeredCard, next).inOrder()
        assertThat(SteeredCards.standing(listOf(steeredCard, next), QueuePlacement.NONE)).containsExactly(next)
        assertThat(SteeredCards.accountRows(listOf(row, other), listOf(steeredCard, next))).containsExactly(other)

        // Still steering: the account holds the message but has not taken the steer, so the card stands whatever the placement says.
        val steeringCard = waiting.copy(isSending = true, steer = SteerPhase.STEERING, steerFollowupId = "fu-1")
        assertThat(SteeredCards.standing(listOf(steeringCard), QueuePlacement.NONE)).containsExactly(steeringCard)
        assertThat(SteeredCards.accountRows(listOf(row), listOf(steeringCard))).isEmpty()
        // A plain queue passes through untouched.
        assertThat(SteeredCards.accountRows(listOf(row, other), listOf(next))).containsExactly(row, other).inOrder()
    }
}
