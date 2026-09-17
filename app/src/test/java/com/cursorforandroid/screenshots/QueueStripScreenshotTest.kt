package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.AccountQueueRows
import com.cursorforandroid.ui.conversation.QueuedFollowUps
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The follow-up strip above the composer while a turn is under way: the queue where Cursor's own clients keep it.
 * Two frames — the account's queue in Extended mode with steer now, send now and the reorder menu on each row, and
 * the device's queue in the default mode — over the composer that filed them. Same device qualifiers as
 * [AppScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueStripScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private val accountQueue = listOf(
        PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS),
        PendingFollowup("fu-2", "And a changelog line under Unreleased", 2_000L, AgentSource.API),
    )

    private val deviceQueue = listOf(
        QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L),
        QueuedFollowUp("q-2", "Attach the before and after screenshots to the PR", images = listOf(DraftImage("img-1", PromptImage(ByteArray(0), "image/png"))), queuedAtMillis = 2_000L),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun accountQueueStrip() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AccountQueueRows(
                            queue = accountQueue,
                            inFlightIds = emptySet(),
                            onSendNow = {},
                            onRemove = {},
                            onUpdate = { _, _ -> },
                            onEditing = { _, _ -> },
                            onSteerNow = {},
                            onMove = { _, _ -> },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (queues on your account)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        capture("63_composer_queue_strip")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun deviceQueueStrip() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QueuedFollowUps(
                            queue = deviceQueue,
                            thumbnails = emptyMap(),
                            onEdit = {},
                            onSteer = {},
                            onRemove = {},
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (sends when the turn ends)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        capture("65_composer_device_queue_strip")
    }
}
