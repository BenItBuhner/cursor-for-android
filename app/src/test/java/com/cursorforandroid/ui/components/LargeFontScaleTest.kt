package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Chrome rows are sized in dp and filled with sp text, so at the system's largest font setting a title over a
 * subtitle needs about 68dp inside a 44dp row and the subtitle is cut off. The rows carry their designed height as
 * a minimum now, and this drives that contract with content of a known size — Robolectric measures text against
 * stub font metrics, so asserting on a scaled line height would be measuring the shadow, not the layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class LargeFontScaleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun heightOf(tag: String): Float =
        compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    @Test
    fun `a chat header grows for content taller than its designed height`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorHeader(
                    modifier = Modifier.testTag("header"),
                    title = "Fix the composer caret",
                    subtitle = "cursor-for-android on main",
                    leading = { Box(Modifier.testTag("tall").width(24.dp).height(72.dp)) },
                )
            }
        }
        assertThat(heightOf("header")).isAtLeast(72f)
        assertThat(heightOf("tall")).isEqualTo(72f)
    }

    @Test
    fun `a header keeps its designed height when the content fits`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorHeader(modifier = Modifier.testTag("header"), title = "Settings")
            }
        }
        assertThat(heightOf("header")).isWithin(1f).of(44f)
    }

    @Test
    fun `a sheet header grows for content taller than its designed height`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SheetHeader(
                    title = "MCP Servers",
                    modifier = Modifier.testTag("sheet"),
                    leading = { Box(Modifier.width(24.dp).height(80.dp)) },
                )
            }
        }
        assertThat(heightOf("sheet")).isAtLeast(80f)
    }

    @Test
    fun `a sheet header keeps its designed height when the content fits`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SheetHeader(title = "MCP Servers", modifier = Modifier.testTag("sheet")) }
        }
        assertThat(heightOf("sheet")).isWithin(1f).of(52f)
    }
}
