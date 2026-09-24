package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pull to catch up through the transcript's own scroll ([readerScrolling]) with a real drag: a chat that
 * overflows the screen, followed at its newest row, and one short enough to fit — whose list cannot scroll at all,
 * which the platform's scrollable would otherwise never hand to the overscroll.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatchUpGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var pulls = 0
    private lateinit var pull: CatchUpPull

    private fun transcript(rows: Int) {
        compose.setContent {
            val density = LocalDensity.current
            pull = androidx.compose.runtime.remember { with(density) { CatchUpPull(CatchUpPullThreshold.toPx()) } }
            val list = rememberLazyListState()
            val scroll = rememberTranscriptScroll(list, key = "chat")
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("transcript")
                        .readerScrolling(scroll, pull = pull, canCatchUp = { true }, onCatchUp = { pulls++ }),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it }) { Text("Row $it", Modifier.fillMaxWidth().height(56.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pullUp() = compose.onNodeWithTag("transcript").performTouchInput { swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 900) }

    @Test
    fun `a chat that overflows the screen, at its newest row, is caught up by a pull past it`() {
        transcript(rows = 60)
        pullUp()
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
        assertThat(pull.holding).isFalse()
    }

    @Test
    fun `a chat short enough to fit the screen is caught up by the same pull`() {
        transcript(rows = 4)
        pullUp()
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `a drag down a short chat never pulls`() {
        transcript(rows = 4)
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown(startY = top + 4f, endY = bottom - 4f, durationMillis = 900) }
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(0)
    }
}
