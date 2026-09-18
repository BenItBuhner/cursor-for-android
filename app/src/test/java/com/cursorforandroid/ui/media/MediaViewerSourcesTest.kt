package com.cursorforandroid.ui.media

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Where the viewer's pixels come from: a URL in default mode, whose screen-sized decode comes off the disk cache
 * the thumbnail's fetch filled rather than off the network again; and a `/cursor/stores/…` path in Extended mode,
 * read once through the account's presigned read and kept.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerSourcesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private val state = MediaViewerState(null)
    private val png: ByteArray = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()

    @After
    fun tearDown() = server.shutdown()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [condition], idling the looper on every check: the loader answers on the main looper, and a
     * condition that only reads state would otherwise never see the frame that moves it.
     */
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    @Test
    fun `a URL's full-size decode is served from the disk cache the thumbnail filled`() {
        repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))) }
        val url = server.url("/shots/tab.png").toString()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }))
        val entries = listOf(MediaEntry(url, MediaEntry.Kind.Image, "Tab layout"))
        compose.setContent { ViewerScene(state, loader, entries) }
        settle { compose.onAllNodes(hasContentDescription("Tab layout")).fetchSemanticsNodes().size == 1 }
        assertThat(server.requestCount).isEqualTo(1)

        compose.onAllNodes(hasContentDescription("Tab layout"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        compose.waitForIdle()
        // The page asked for the screen's size — more than the inline decode — and got it without another fetch.
        val image = compose.onAllNodes(hasTestTag("viewer-image-0"))[0].fetchSemanticsNode().boundsInRoot
        assertThat(image.width).isWithin(2f).of(compose.onRoot().fetchSemanticsNode().boundsInRoot.width)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** The account's store reads: the Project's store, and the fixture image behind a presigned URL served here. */
    private inner class StoreOnTheWire(private val store: String) : AgentStoreApi {
        val presigned = mutableListOf<String>()
        override suspend fun storeFor(sourceId: String): String? = "st-proj".takeIf { sourceId == store }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = "# $relativePath"
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead {
            presigned += "$target:$relativePath"
            return PresignedStoreRead(relativePath, server.url("/signed/$relativePath").toString(), null)
        }
    }

    @Test
    fun `a store figure in Extended mode opens in the viewer off one presigned read`() {
        val fixture = CoordinatorFixtures.json("store_paths_message.json")
        val store = fixture.getValue("storeId").jsonPrimitive.content
        val imagePath = fixture.getValue("imagePath").jsonPrimitive.content
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))) }
        val wire = StoreOnTheWire(store)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The bytes are kept on the device once read, as the app keeps them, so the page's decode never asks again.
        val files = StoreFileRepository(api = { wire }, capabilities = { Capabilities.EXTENDED }, blobs = File(context.cacheDir, "store-blobs").apply { mkdirs() })
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files }
        val entries = listOf(MediaEntry(imagePath, MediaEntry.Kind.Image, "Tab layout, landscape"))
        compose.setContent { ViewerScene(state, loader, entries, agentId = store, canReadStores = true) }
        settle { compose.onAllNodes(hasContentDescription("Tab layout, landscape")).fetchSemanticsNodes().size == 1 }

        compose.onAllNodes(hasContentDescription("Tab layout, landscape"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        compose.waitForIdle()
        assertThat(state.current?.src).isEqualTo(imagePath)
        // The store was asked once, for the thumbnail; the page decoded the bytes the repository kept.
        assertThat(wire.presigned).containsExactly("Store(storeId=st-proj):media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(server.requestCount).isEqualTo(1)
    }
}
