package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * Chrome rows are sized in dp and filled with sp text, so at the system's largest font setting a title over a
 * subtitle needs about 68dp inside a 44dp row and the subtitle is cut off. The rows carry their designed height as
 * a minimum now, and this drives that contract with content of a known size — Robolectric's default text measurement
 * returns stub font metrics, so asserting on a scaled line would be measuring the shadow, not the layout. The
 * composer's model chip has no content to stand in for its label, so those two cases ask for real metrics
 * ([GraphicsMode.Mode.NATIVE]) and set the font scale the way the system does, on the configuration.
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

    private fun composer(footerExtra: (@Composable RowScope.() -> Unit)? = null) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    ComposerBox(
                        value = "",
                        onValueChange = {},
                        placeholder = "Describe a task",
                        onSend = {},
                        modifier = Modifier.testTag("composer"),
                        modelLabel = ModelLabel,
                        onModel = {},
                        footerExtra = footerExtra,
                    )
                    // One unconstrained line of the chip's own style: what its label asks for at this font scale.
                    Text("Reference line", style = CursorTheme.typography.base, maxLines = 1, modifier = Modifier.testTag("line"))
                }
            }
        }
    }

    private fun modelChipHeight(): Float =
        compose.onNodeWithText(ModelLabel).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    /**
     * The footer's own controls are dp-sized discs, but the model chip's label is sp, and the footer's height was the
     * parent constraint that held the chip to 28dp and cut the label off. Text has to be measured against real font
     * metrics for the scale to reach the layout, hence [GraphicsMode.Mode.NATIVE], and the line is measured rather
     * than assumed so the chip is held to what its content asks for and not to this host's font.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(fontScale = 2f)
    fun `a composer footer lets the model chip grow at the largest system font`() {
        composer()
        assertThat(heightOf("line")).isGreaterThan(CursorDimens.composerFooter.value)
        assertThat(modelChipHeight()).isWithin(0.5f).of(heightOf("line"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a composer footer holds the model chip at its designed height at the default font`() {
        composer()
        assertThat(heightOf("line")).isLessThan(CursorDimens.composerFooter.value)
        assertThat(modelChipHeight()).isWithin(1f).of(28f)
    }

    @Test
    fun `a composer footer grows for content taller than its designed height`() {
        composer { Box(Modifier.testTag("tall").width(24.dp).height(72.dp)) }
        assertThat(heightOf("tall")).isEqualTo(72f)
        // 12dp of padding over a 22dp field, a 10dp gap, the footer, then 10dp: 82dp were the footer held to 28dp.
        assertThat(heightOf("composer")).isWithin(1f).of(126f)
    }

    private companion object {
        const val ModelLabel = "Claude 4.6 Sonnet (Thinking)"
    }
}
