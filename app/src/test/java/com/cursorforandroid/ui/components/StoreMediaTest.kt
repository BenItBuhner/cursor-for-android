package com.cursorforandroid.ui.components

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
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

/**
 * A `/cursor/stores/…` figure in a reply (the coordinator's own image line, see `store_paths_message.json`): drawn
 * from the store through the account's presigned read in Extended mode; a card that opens the Project on cursor.com
 * without the account, where "Image isn't available" used to be.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StoreMediaTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val imagePath = fixture.getValue("imagePath").jsonPrimitive.content
    private val markdown = fixture.getValue("markdown").jsonPrimitive.content
    private val server = MockWebServer()

    /** The account's store reads: the Project's store, and the bytes of the fixture image behind a presigned URL served here. */
    private inner class StoreOnTheWire : AgentStoreApi {
        val presigned = mutableListOf<String>()
        override suspend fun storeFor(sourceId: String): String? = "st-proj".takeIf { sourceId == store }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = "# $relativePath"
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead {
            presigned += "$target:$relativePath"
            return PresignedStoreRead(relativePath, server.url("/signed/$relativePath").toString(), null)
        }
    }

    private val wire = StoreOnTheWire()

    private fun loader(capabilities: Capabilities): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = StoreFileRepository(api = { wire }, capabilities = { capabilities })
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files }
    }

    private fun serveImage() {
        val bytes = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `in Extended mode the coordinator's store image is fetched through the presigned read and drawn inline`() {
        serveImage()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val lightbox = rememberLightboxState(store)
                CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext(store, loader(Capabilities.EXTENDED), lightbox, canReadStores = true)) {
                    MarkdownText(markdown.lines().first { it.startsWith("![") })
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view")).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasTestTag("store-file-card")).assertCountEquals(0)
        assertThat(wire.presigned).containsExactly("Store(storeId=st-proj):media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(server.takeRequest().path).isEqualTo("/signed/media/ui-parity/tab-landscape-icon-only-project.png")
    }

    @Test
    fun `without the account the figure is a card naming the file that opens the Project on the web`() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val lightbox = rememberLightboxState(store)
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalMarkdownMedia provides MarkdownMediaContext(store, loader(Capabilities.DOCUMENTED), lightbox, canReadStores = false),
                ) {
                    MarkdownText(markdown)
                }
            }
        }
        compose.waitForIdle()
        // Two store figures and the recording: three cards, no "isn't available".
        compose.onAllNodes(hasTestTag("store-file-card")).assertCountEquals(3)
        compose.onAllNodes(hasText("Image isn't available")).assertCountEquals(0)
        compose.onAllNodes(hasText("Video isn't available")).assertCountEquals(0)
        compose.onNodeWithText("Tab layout, landscape, icon-only rail, Project view").assertIsDisplayed()
        compose.onNodeWithText(imagePath).assertIsDisplayed()
        compose.onNodeWithText("Tab layout, landscape, icon-only rail, Project view").performClick()
        assertThat(opened).containsExactly("https://cursor.com/agents/$store")
        assertThat(wire.presigned).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a tapped store link or card goes where the screen sends it`() {
        val sent = mutableListOf<StorePath>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val lightbox = rememberLightboxState(store)
                val media = MarkdownMediaContext(store, loader(Capabilities.DOCUMENTED), lightbox, canReadStores = false, onOpenStorePath = { sent += it })
                CompositionLocalProvider(LocalMarkdownMedia provides media) {
                    MarkdownText("![The board after the merge](/cursor/stores/self/media/board.png)")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("store-file-card").performClick()
        assertThat(sent.map { it.text }).containsExactly("/cursor/stores/$store/media/board.png")
    }

    /** The service's own words for a read it refuses (`store_presign_error.json`, what v0.3.19 showed) are the card's title, with a Retry. */
    @Test
    fun `a read the store refuses is still a card, saying what the service said, with a retry`() {
        val fixture = CoordinatorFixtures.json("store_presign_error.json")
        val message = fixture.getValue("body").jsonObject.getValue("message").jsonPrimitive.content
        val refusing = object : AgentStoreApi by wire {
            override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead =
                throw ConnectRpcException(fixture.getValue("httpCode").jsonPrimitive.int, "invalid_argument", message)
        }
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val files = StoreFileRepository(api = { refusing }, capabilities = { Capabilities.EXTENDED })
                val loader = remember { MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files } }
                val lightbox = rememberLightboxState(store)
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalMarkdownMedia provides MarkdownMediaContext(store, loader, lightbox, canReadStores = true),
                ) {
                    MarkdownText(markdown.lines().first { it.startsWith("![") })
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("store-file-card")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText(message).performClick()
        assertThat(opened).containsExactly("https://cursor.com/agents/$store")
    }
}
