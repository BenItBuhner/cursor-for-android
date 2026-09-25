package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The headers in a desktop window's caption bar, as [CaptionBarHost] provides it: a 40dp bar with the app menu over
 * the first 160dp and the window controls over the last 200dp of a 1000dp window (mdpi, so pixels are dp). Each
 * header stands in the bar's row, its buttons centred on the bar and never under a system control, wherever the
 * header is in the window; with no caption bar the headers keep their usual height.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1000dp-h700dp-mdpi")
class CaptionHeaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appMenu = IntRect(0, 0, 160, 40)
    private val windowControls = IntRect(800, 0, 1000, 40)
    private val bar = CaptionBar(40, listOf(appMenu, windowControls))

    private fun header(bar: CaptionBar?, railWidth: Int = 0, chat: Boolean = false) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalCaptionBar provides bar) {
                    Row(Modifier.fillMaxSize()) {
                        if (railWidth > 0) Box(Modifier.width(railWidth.dp))
                        Box(Modifier.weight(1f)) {
                            val leading: @Composable RowScope.() -> Unit = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) }
                            val trailing: @Composable RowScope.() -> Unit = { FlatIconButton(CursorIcons.More, "More", onClick = {}) }
                            if (chat) ChatHeader("Chat", leading = leading, trailing = trailing) else CursorHeader(leading = leading, trailing = trailing)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String): DpRect = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()

    private fun assertClearOfControls(description: String) {
        val button = bounds(description)
        for (control in bar.controls) {
            val overlaps = button.left.value < control.right && control.left < button.right.value
            assertWithMessage("$description at $button under the system control at $control").that(overlaps).isFalse()
        }
    }

    private fun assertCentredOnBar(description: String) {
        val button = bounds(description)
        assertThat((button.top + button.bottom).value / 2).isWithin(0.5f).of(bar.heightPx / 2f)
    }

    @Test
    fun `a full-width header stands in the bar clear of the app menu and the window controls`() {
        header(bar)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertClearOfControls("Open sidebar")
        assertClearOfControls("More")
        assertCentredOnBar("Open sidebar")
        assertCentredOnBar("More")
    }

    @Test
    fun `a chat header stands in the bar, and beside the rail only the window controls push it in`() {
        header(bar, railWidth = 300, chat = true)
        assertThat(compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertThat(bounds("Open sidebar").left).isLessThan(310.dp)
        assertClearOfControls("More")
        assertCentredOnBar("More")
    }

    @Test
    fun `without a caption bar the headers keep their usual rows`() {
        header(null)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(CursorDimens.headerHeight)
        assertThat(bounds("Open sidebar").left).isLessThan(20.dp)
        assertThat(bounds("More").right).isGreaterThan(980.dp)
    }
}
