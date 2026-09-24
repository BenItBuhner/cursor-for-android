package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Where the pull's tab stands: under the composer's top edge at rest, drawn out from beneath it as far as the pull
 * reveals — its visible part standing on that edge, the rest still under it — never further than its corners, which
 * stay tucked, and kept all the way out while the answer shows, then sunk and gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CatchUpRevealTest {

    @get:Rule
    val compose = createComposeRule()

    private var status by mutableStateOf<CatchUpStatus>(CatchUpStatus.Idle)
    private lateinit var pull: CatchUpPull
    private lateinit var density: Density

    private fun screen() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                density = LocalDensity.current
                pull = remember { with(density) { CatchUpPull(CatchUpPullThreshold.toPx(), CatchUpPullReveal.toPx()) } }
                val reveal = rememberCatchUpReveal(pull, status)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        CatchUpIndicator(pull, status, reveal, onDismiss = {}, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                    }
                    Box(Modifier.fillMaxWidth().height(96.dp).testTag("composer"))
                }
            }
        }
        compose.waitForIdle()
    }

    private val composerTop get() = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.top

    private val tab get() = compose.onNodeWithTag(CATCH_UP_TEST_TAG).fetchSemanticsNode()

    private val tabShown get() = compose.onAllNodesWithTag(CATCH_UP_TEST_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun px(dp: Float) = with(density) { dp.dp.toPx() }

    @Test
    fun `the pull draws the tab out from under the composer's edge as far as it reveals, its corners kept under`() {
        screen()
        assertThat(tabShown).isFalse()

        pull.stretch(px(20f))
        compose.waitForIdle()
        // What shows stands on the composer's edge, and is as tall as the pull has revealed.
        assertThat(tab.boundsInRoot.bottom).isWithin(1f).of(composerTop)
        assertThat(tab.boundsInRoot.height).isWithin(1f).of(pull.revealPx)
        assertThat(pull.revealPx).isGreaterThan(px(14f))

        pull.stretch(px(400f))
        compose.waitForIdle()
        // All the way out: the whole tab shows but for its corners, still under the edge.
        val unclippedBottom = tab.positionInRoot.y + tab.size.height
        assertThat(unclippedBottom).isWithin(1f).of(composerTop + px(CursorDimens.menuRadius.value))
        assertThat(tab.boundsInRoot.height).isWithin(1f).of(pull.tabPx)
        assertThat(tab.boundsInRoot.bottom).isWithin(1f).of(composerTop)
    }

    @Test
    fun `an armed release keeps the whole tab out while the answer shows, then sinks it and takes it away`() {
        screen()
        pull.stretch(pull.thresholdPx * 1.5f)
        compose.waitForIdle()
        assertThat(pull.release()).isTrue()
        status = CatchUpStatus.Checking
        compose.waitForIdle()
        assertThat(tab.boundsInRoot.height).isWithin(1f).of(pull.tabPx)

        status = CatchUpStatus.Done(newMessages = 2, changed = true)
        compose.waitForIdle()
        assertThat(tab.boundsInRoot.height).isWithin(1f).of(pull.tabPx)
        assertThat(tab.boundsInRoot.bottom).isWithin(1f).of(composerTop)

        status = CatchUpStatus.Idle
        compose.waitForIdle()
        assertThat(tabShown).isFalse()
    }

    @Test
    fun `a release short of the threshold sinks the tab back under with nothing asked`() {
        screen()
        pull.stretch(pull.thresholdPx * 0.5f)
        compose.waitForIdle()
        assertThat(tabShown).isTrue()
        assertThat(pull.release()).isFalse()
        compose.waitForIdle()
        assertThat(tabShown).isFalse()
        assertThat(pull.pulls).isEqualTo(0)
    }
}
