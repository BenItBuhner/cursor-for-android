package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendMotion
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * A queued message the run takes, shown as its sending bubble the moment the row goes out and filed by the account
 * after the flight has landed, filmed a 16 ms frame at a time on a held clock: the copy lands at the sending look and
 * the bubble then fades up to full strength over the screen's sent fade. Runs only with `CONFIRM_FADE_FILM_DIR` set,
 * each frame written there as a PNG (the confirm fade demo's frames).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ConfirmFadeFilmTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = System.getenv("CONFIRM_FADE_FILM_DIR")?.let(::File)
    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        assumeTrue(dir != null)
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun draw(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The row goes out at frame 10 as the sending bubble; the account files it at frame 40, after the copy has landed. */
    @Test
    fun confirmFade() {
        val text = "Also check the release build, not just debug"
        scene.queue += listOf(QueuedFollowUp("q-1", text, queuedAtMillis = 0L), QueuedFollowUp("q-2", "Then write up what changed for the release notes", queuedAtMillis = 0L))
        scene.show(motion, withSentFades = true)
        val out = File(dir, "confirm").apply { deleteRecursively(); mkdirs() }
        for (f in 0..90) {
            when (f) {
                10 -> compose.runOnUiThread {
                    scene.queue.removeAll { it.id == "q-1" }
                    scene.messages += UserMessage("local-q", text, isPending = true)
                }
                40 -> scene.replace("local-q", UserMessage("local-q", text))
            }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            draw(File(out, "f_%04d.png".format(f)))
        }
    }
}
