package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.conversation.SendWhileUploadingScene
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The chat's Send while an attached file is still going up (COMP-7). Mid-turn the message would wait in the account's
 * queue, which needs the file up: Send is dimmed until it is, then white. Idle, Send stays white — the message goes
 * into a bubble that finishes the upload. Same device qualifiers as [AppScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SendWhileUploadingScreenshotTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val scene = SendWhileUploadingScene(compose, appVersion = SCREENSHOT_APP_VERSION)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(scene)

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun locale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun queueHeld(mode: ThemeMode, name: String) {
        scene.show(running = true, mode = mode)
        scene.attachUploading("Also look at this crash report")
        scene.sendButton.assertIsNotEnabled()
        capture(name)
    }

    @Test
    fun queueHeldDark() = queueHeld(ThemeMode.Dark, "855_send_while_uploading_queue_held_dark")

    @Test
    fun queueHeldLight() = queueHeld(ThemeMode.Light, "856_send_while_uploading_queue_held_light")

    @Test
    fun queueReady() {
        scene.show(running = true)
        scene.attachUploading("Also look at this crash report")
        scene.finishUpload()
        scene.sendButton.assertIsEnabled()
        capture("857_send_while_uploading_queue_ready")
    }

    @Test
    fun bubbleSendsMidUpload() {
        scene.show(running = false)
        scene.attachUploading("Here is the crash report")
        scene.sendButton.assertIsEnabled()
        capture("858_send_while_uploading_bubble")
    }
}
