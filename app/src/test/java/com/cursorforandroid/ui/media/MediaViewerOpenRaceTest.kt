package com.cursorforandroid.ui.media

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ArtifactPaths
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The viewer's open against decodes that finish on a background thread at the worst moments: while the main
 * looper is idle and nobody is pumping it, and part-way through the open transform. On a phone the main looper
 * runs of its own accord, so a completion that only needs a looper turn is seen at once; what the viewer must never
 * do is depend on one that needs a *touch* — a state written where Compose is not told, a frame nobody asked for.
 * Under the test harness the composition's coroutines run unconfined, so a completion on a worker thread resumes
 * the composition there; the loader has to bring every answer back to the main thread itself, or the harness (and
 * anything else that assumes a main-confined composition) races.
 *
 * Every artifact here is fetched through a URL the test hands out when it chooses, from a thread of its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerOpenRaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** An artifact endpoint whose answers wait on gates the test opens: one gate per artifact path. */
    private class GatedApi : FakeCursorApi() {
        val gates = ConcurrentHashMap<String, CompletableDeferred<String>>()
        fun gate(path: String): CompletableDeferred<String> = gates.getOrPut(path) { CompletableDeferred() }
        override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto = DownloadArtifactResponseDto(url = gate(path).await())
    }

    private val api = GatedApi()
    private lateinit var wideFile: String
    private lateinit var squareFile: String
    private lateinit var tallFile: String

    private fun loader(): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { api }))
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun nodes(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    private fun settle(timeout: Long = 30_000, condition: () -> Boolean) = compose.waitUntil(timeout) {
        compose.waitForIdle()
        condition()
    }

    /** Opens [path]'s gate to [file] from a thread that is not the main one, after [afterMillis] of real time. */
    private fun releaseFromBackground(path: String, file: String, afterMillis: Long = 0L) {
        Thread {
            if (afterMillis > 0) Thread.sleep(afterMillis)
            api.gate(path).complete(file)
        }.apply { name = "decode-release" }.start()
    }

    private fun artifact(name: String) = ArtifactPaths.VM_ROOT + name

    private val wide get() = artifact("race-wide.png")
    private val square get() = artifact("race-square.png")
    private val tall get() = artifact("race-tall.png")

    private fun entries() = listOf(
        MediaEntry(wide, MediaEntry.Kind.Image, "Wide"),
        MediaEntry(square, MediaEntry.Kind.Image, "Square"),
        MediaEntry(tall, MediaEntry.Kind.Image, "Tall"),
    )

    private var iteration by mutableIntStateOf(0)
    private var state = MediaViewerState(null)

    @Test
    fun `a page's decode that lands on a worker while the main looper sits idle is shown on the looper's next turn`() {
        wideFile = ViewerFixtures.png("race-wide.png", 640, 360, 0xFF1E2A3A.toInt())
        squareFile = ViewerFixtures.png("race-square.png", 400, 400, 0xFF3A1E2A.toInt())
        tallFile = ViewerFixtures.png("race-tall.png", 360, 640, 0xFF2A3A1E.toInt())
        val loader = loader()
        compose.setContent { key(iteration) { ViewerScene(state, loader, entries()) } }
        // The tapped thumbnail decodes now; the other two stay gated, so nothing else is in flight at the tap.
        api.gate("artifacts/race-wide.png").complete(wideFile)
        settle { nodes("Wide") == 1 }
        compose.onAllNodes(hasContentDescription("Wide"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        // The neighbour page is composed ahead, waiting on its gate: no picture yet.
        assertThat(exists("viewer-image-1")).isFalse()

        // Its decode completes on a worker while the main thread does nothing at all — not idling, not pumping.
        // (The tall artifact's gate opens too: the repository's URL locks are striped, and a gate left shut would
        // hold a stripe the square shares, which is a property of the reproduction rather than of the viewer.)
        releaseFromBackground("artifacts/race-square.png", squareFile)
        releaseFromBackground("artifacts/race-tall.png", tallFile)
        Thread.sleep(300)

        // One turn of the looper and one frame — what a phone gives of its own accord — and the picture is there.
        compose.waitForIdle()
        settle(5_000) { exists("viewer-image-1") }
        assertThat(exists("viewer-image-1")).isTrue()
    }

    @Test
    fun `decodes landing on workers during the open transform never leave the viewer short of Open`() {
        wideFile = ViewerFixtures.png("race-wide.png", 640, 360, 0xFF1E2A3A.toInt())
        squareFile = ViewerFixtures.png("race-square.png", 400, 400, 0xFF3A1E2A.toInt())
        tallFile = ViewerFixtures.png("race-tall.png", 360, 640, 0xFF2A3A1E.toInt())
        val random = Random(7)
        compose.setContent { key(iteration) { ViewerScene(state, remember { loader() }, entries()) } }
        repeat(ROUNDS) { round ->
            // A fresh scene, viewer and gates each round: the two thumbnails beside the tapped one are still
            // decoding when it is tapped, and finish on their workers somewhere inside the 300 ms transform.
            api.gates.clear()
            state = MediaViewerState(null)
            iteration = round + 1
            compose.waitForIdle()
            api.gate("artifacts/race-wide.png").complete(wideFile)
            settle { nodes("Wide") == 1 }
            compose.onAllNodes(hasContentDescription("Wide"))[0].performClick()
            releaseFromBackground("artifacts/race-square.png", squareFile, afterMillis = random.nextLong(0, 120))
            releaseFromBackground("artifacts/race-tall.png", tallFile, afterMillis = random.nextLong(0, 120))
            settle(10_000) { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
            // The square is drawn twice once its page lands: the thumbnail and the page's picture.
            settle(10_000) { nodes("Square") >= 1 && nodes("Tall") >= 1 && exists("viewer-image-1") }
            compose.onNodeWithContentDescription("Close").performClick()
            settle(10_000) { !state.isOpen }
        }
    }

    private companion object {
        const val ROUNDS = 12
    }
}
