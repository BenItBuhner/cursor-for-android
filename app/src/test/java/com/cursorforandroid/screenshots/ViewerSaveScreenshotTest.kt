package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaSaves
import com.cursorforandroid.ui.media.ViewerNotice
import com.cursorforandroid.ui.media.ViewerTopBar
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
 * The viewer's Save button through a recording's save: ready, the ring filling with a known size, spinning without
 * one, saved with its notice, and failed with Retry. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h360dp-night-420dpi")
class ViewerSaveScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val clip = MediaEntry("https://example.com/walkthrough.mp4", MediaEntry.Kind.Video, fileName = "walkthrough.mp4", durationMs = 12_000L)

    private fun shoot(name: String, state: MediaSaves.State, notice: String? = null, action: String? = null) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF262626)))
                    ViewerTopBar(index = 2, count = 3, entry = clip, onClose = {}, onShare = {}, save = state, onSave = {}, onOpenWith = {})
                    if (notice != null) ViewerNotice(notice, Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 28.dp), action = action)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun ready() = shoot("580_viewer_save_ready", MediaSaves.State.Idle)

    @Test
    fun progress() = shoot("581_viewer_save_progress", MediaSaves.State.Working(0.45f))

    @Test
    fun indeterminate() = shoot("582_viewer_save_indeterminate", MediaSaves.State.Working(null))

    @Test
    fun saved() = shoot("583_viewer_save_saved", MediaSaves.State.Saved("Saved to Movies"), notice = "Saved to Movies")

    @Test
    fun failed() = shoot(
        "584_viewer_save_failed",
        MediaSaves.State.Failed("The download answered 503."),
        notice = "Couldn't save: The download answered 503.",
        action = "Retry",
    )
}
