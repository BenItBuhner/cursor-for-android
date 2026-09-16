package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A reply with inline code and a fenced block, as the transcript renders them: the block wears cursor.com's strip —
 * language on the left, the copy button on the right — over code that scrolls under it. Written to `screenshots/`
 * beside the walkthrough; CI compares it pixel for pixel (`verifyRoborazziDebug`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CodeBlockScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun codeBlockWithCopy() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(20.dp).testTag("scene")) {
                        MarkdownText(REPLY)
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Copy code")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "34_code_block_copy.png").path, RoborazziOptions())
    }

    private companion object {
        val REPLY = """
            If you want to try something in the meantime: this only removes `node_modules` and bun's cache, and often clears this exact error:

            ```bash
            cd ~/.cesium/source && bun pm cache rm && rm -rf node_modules server/node_modules
            curl -fsSL https://raw.githubusercontent.com/BenItBuhner/Cesium/main/install.sh | bash
            ```

            Then re-run `cesium-server update` and paste the output here.
        """.trimIndent()
    }
}
