package com.cursorforandroid.ui.components

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Bennett's Galaxy Tab S8 (SM-X700, 2560×1600): store figures that never came on a device that had not resolved the
 * Project's store yet, and came "on their own" later. On a tablet-sized window: the figures of a cold device draw —
 * the one whose key shared a lock with its owner's store among them — at one source pixel per dp; a read with no
 * answer says what it waits on, then fails with Retry at its deadline instead of spinning; and every figure is in the
 * diagnostics with its source, state, step, wait and sizes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletStoreMediaTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val figure = fixture.getValue("markdown").jsonPrimitive.content.lines().first { it.startsWith("![") }
    private val server = MockWebServer()
    private val png = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()

    private inner class StoreOnTheWire(private val answers: Boolean = true) : AgentStoreApi {
        val presigned = mutableListOf<String>()
        override suspend fun storeFor(sourceId: String): String? = "st-proj".takeIf { sourceId == store }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = "# $relativePath"
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead {
            presigned += relativePath
            if (!answers) awaitCancellation()
            return PresignedStoreRead(relativePath, server.url("/signed/$relativePath").toString(), null)
        }
    }

    /** A device that never resolved the store: no disk cache, nothing in memory. */
    private fun coldLoader(wire: AgentStoreApi): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = StoreFileRepository(api = { wire }, capabilities = { Capabilities.EXTENDED })
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files }
    }

    private fun show(loader: MediaLoader, markdown: String) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext(store, loader, canReadStores = true)) {
                    MarkdownText(markdown)
                }
            }
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `on a cold tablet both store figures draw, the one sharing its owner's lock too, one source pixel per dp`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
        }
        val wire = StoreOnTheWire()
        val loader = coldLoader(wire)
        val colliding = "/cursor/stores/$store/media/transcript-store-images.png"
        assertThat(("store:$store:media/transcript-store-images.png".hashCode() and Int.MAX_VALUE) % StoreFileRepository.STRIPES)
            .isEqualTo((store.hashCode() and Int.MAX_VALUE) % StoreFileRepository.STRIPES)
        show(loader, "![Transcript store images]($colliding)\n\n$figure")

        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Transcript store images")).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view")).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(hasTestTag("store-file-card")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("media-stalled")).assertCountEquals(0)
        // 560×350 source pixels at xhdpi: 560×350 dp in a 1280 dp window, under the 360 dp height cap.
        for (alt in listOf("Transcript store images", "Tab layout, landscape, icon-only rail, Project view")) {
            val size = compose.onNode(hasContentDescription(alt)).fetchSemanticsNode().size
            assertThat(size.width).isEqualTo(1120)
            assertThat(size.height).isEqualTo(700)
        }
        val lines = loader.loads.lines(store)
        assertThat(lines.map { it.label }).containsExactly("transcript-store-images.png", "tab-landscape-icon-only-project.png")
        lines.forEach { line ->
            assertThat(line.kind).isEqualTo("store")
            assertThat(line.state).isEqualTo("ready")
            assertThat(line.stage).isEqualTo("decode")
            assertThat(line.asked).isEqualTo("1600x840")
            assertThat(line.decoded).isEqualTo("560x350")
            assertThat(line.error).isNull()
        }
    }

    @Test
    fun `a store read with no answer says what it waits on, then fails with Retry at its deadline, and Retry asks again`() {
        val wire = StoreOnTheWire(answers = false)
        val loader = coldLoader(wire)
        compose.mainClock.autoAdvance = false
        show(loader, figure)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertThat(wire.presigned).containsExactly("media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(compose.onAllNodes(hasContentDescription("Loading image")).fetchSemanticsNodes()).hasSize(1)
        compose.onAllNodes(hasTestTag("media-stalled")).assertCountEquals(0)

        compose.mainClock.advanceTimeBy(STALL_NOTICE_MS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("media-stalled").assertIsDisplayed()
        compose.onNodeWithText("Still loading · Asking for the file's link").assertIsDisplayed()
        assertThat(loader.loads.lines(store).single().let { it.state to it.stage }).isEqualTo("loading" to "link")

        // The load's deadline runs on the main looper's clock.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MediaLoader.IMAGE_DEADLINE_MS))
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onAllNodes(hasTestTag("media-stalled")).assertCountEquals(0)
        compose.onNodeWithTag("store-file-card").assertIsDisplayed()
        compose.onNodeWithText("This image took too long to load").assertIsDisplayed()
        compose.onNodeWithText("No answer after 60s — stuck asking for the file's link.").assertIsDisplayed()
        val line = loader.loads.lines(store).single()
        assertThat(line.state).isEqualTo("timed_out")
        assertThat(line.stage).isEqualTo("link")
        assertThat(line.error).startsWith("This image took too long to load")

        compose.onNodeWithText("Retry").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertThat(wire.presigned).hasSize(2)
        assertThat(compose.onAllNodes(hasContentDescription("Loading image")).fetchSemanticsNodes()).hasSize(1)
        assertThat(loader.loads.lines(store).single().let { it.state to it.attempts }).isEqualTo("loading" to 2)
    }

    @Test
    fun `a download the store refuses is a card with its words and Retry, and failed in the diagnostics, not loading`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        val loader = coldLoader(StoreOnTheWire())
        show(loader, figure)

        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("store-file-card")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("The store answered 500 for this file.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        val line = loader.loads.lines(store).single()
        assertThat(line.state).isEqualTo("failed")
        assertThat(line.stage).isEqualTo("download")
        assertThat(line.error).isEqualTo("The store answered 500 for this file.")
    }
}
