package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.conversation.CaptionFadeMillis
import com.cursorforandroid.ui.conversation.captionFade
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rolling status line a subagent's row and a chat's run caption share: a new wording rolls in over the old one in
 * [RollingText.RollMillis], each wording holds for [RollingText.HoldMillis] however fast the status races, and the
 * newest waiting is the one that shows next. The run caption's fades are drawn from the list's layers, past its
 * composition, so they are pinned by screenshots 660 and 661. The shimmer runs for ever, so the clock is driven by hand.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RollingTextTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var status by mutableStateOf("Working")
    private var working by mutableStateOf(true)

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { RollingText(status) } }
        frames(16)
    }

    private fun frames(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    private fun shows(text: String, count: Int = 1) = compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(count)

    @Test
    fun `a new wording rolls in over the old one, and the old one is gone once it has`() {
        show()
        frames(RollingText.HoldMillis + 32)
        compose.runOnUiThread { status = "Reading file" }
        frames(RollingText.RollMillis / 2L)
        // Mid-roll both are there: one rising out, the other rising in.
        shows("Working")
        shows("Reading file")
        frames(RollingText.RollMillis.toLong() + 64)
        shows("Working", 0)
        shows("Reading file")
    }

    @Test
    fun `a wording holds its time however fast the status races, and the newest waiting shows next`() {
        show()
        compose.runOnUiThread { status = "Reading file" }
        frames(RollingText.HoldMillis / 3)
        compose.runOnUiThread { status = "Thinking" }
        frames(RollingText.HoldMillis / 3)
        // "Working" has not had its time yet, so neither newer wording has come in.
        shows("Working")
        shows("Reading file", 0)
        shows("Thinking", 0)
        frames(RollingText.HoldMillis / 3 + RollingText.RollMillis + 96)
        // "Reading file" was passed over for the newest.
        shows("Working", 0)
        shows("Reading file", 0)
        shows("Thinking")
    }

    @Test
    fun `the run caption leaves the list for a reader as its fade starts, and comes back`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item("row") { BasicText("A reply") }
                    if (working) {
                        item("working") {
                            Box(captionFade().testTag("caption")) { RollingText("Working…") }
                        }
                    }
                }
            }
        }
        frames(CaptionFadeMillis + 64L)
        compose.onAllNodesWithTag("caption").assertCountEquals(1)

        compose.runOnUiThread { working = false }
        frames(CaptionFadeMillis / 2L)
        compose.onAllNodesWithTag("caption").assertCountEquals(0)

        compose.runOnUiThread { working = true }
        frames(32)
        compose.onAllNodesWithTag("caption").assertCountEquals(1)
    }
}
