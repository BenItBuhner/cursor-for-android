package com.cursorforandroid.ui.panel

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
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
 * v0.4.23, Bennett's phone: the panel's `raw_155132.png` tab said "Image isn't available". A Context document's
 * relative picture, like a file tapped in the Context tree, is named by the store it is in —
 * `/cursor/stores/<store id>/…` — and only `bc-…` and `self` mounts were read as stores; the id `ListAgentStores`
 * gives a store, and the `user` and `team` mounts, parsed as nothing to fetch, with no Retry. They are read from
 * their store now, and an address that still names nothing says so inline, as written, with Retry.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelStoreMediaTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = MockWebServer()
    private val chat = "bc-chat"
    private val projectStore = "st-3f2a9c"

    private inner class StoreOnTheWire : AgentStoreApi {
        val presigned = mutableListOf<String>()
        override suspend fun storeFor(sourceId: String): String? = null
        override suspend fun stores(): List<AgentStoreRef> = listOf(
            AgentStoreRef(projectStore, AgentStoreKind.CLOUD, sourceId = "bc-coordinator"),
            AgentStoreRef("st-user-77", AgentStoreKind.USER),
        )
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = ""
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead {
            presigned += "$target:$relativePath"
            return PresignedStoreRead(relativePath, server.url("/signed/$relativePath").toString(), null)
        }
    }

    private val wire = StoreOnTheWire()

    @After
    fun tearDown() = server.shutdown()

    private fun serveImage() {
        val bytes = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
    }

    private fun showTab(tab: PanelTab.Media) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = StoreFileRepository(api = { wire }, capabilities = { Capabilities.EXTENDED })
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext(chat, loader, canReadStores = true)) {
                    MediaTab(tab, PanelActions.None)
                }
            }
        }
    }

    @Test
    fun `a picture a Context document names relatively opens in its tab, read from the store it is in`() {
        serveImage()
        val src = PanelLinks.resolveTarget("media/raw_155132.png", StoreBase.of(projectStore, "notes.md"))!!
        assertThat(src).isEqualTo("/cursor/stores/$projectStore/media/raw_155132.png")
        showTab(PanelTab.Media(src, "raw_155132.png"))
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("raw_155132.png")).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasText("Image isn't available")).assertCountEquals(0)
        assertThat(wire.presigned).containsExactly("Store(storeId=$projectStore):media/raw_155132.png")
        assertThat(server.takeRequest().path).isEqualTo("/signed/media/raw_155132.png")
    }

    @Test
    fun `a picture of the user's store is read from the store the account lists as the user's`() {
        serveImage()
        showTab(PanelTab.Media("/cursor/stores/user/uploads/25177987-8ce3-4647-8e50-298144a0c1d2.png", "25177987-8ce3-4647-8e50-298144a0c1d2.png"))
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("25177987-8ce3-4647-8e50-298144a0c1d2.png")).fetchSemanticsNodes().size == 1 }
        assertThat(wire.presigned).containsExactly("Store(storeId=st-user-77):uploads/25177987-8ce3-4647-8e50-298144a0c1d2.png")
    }

    @Test
    fun `an address nothing here can read says so inline, as written, with a retry`() {
        showTab(PanelTab.Media("sandbox:/mnt/data/raw_155132.png", "raw_155132.png"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("This image's address can't be read here")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("sandbox:/mnt/data/raw_155132.png").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onAllNodes(hasText("Image isn't available")).assertCountEquals(0)
        assertThat(wire.presigned).isEmpty()
    }

    @Test
    fun `a picture tapped in the Context tree or Recents opens as a media tab on its store path, never a presigned link`() {
        val opened = mutableListOf<String>()
        val actions = object : PanelActions by PanelActions.None {
            override fun openMedia(src: String, name: String, isVideo: Boolean) { opened += "media:$src:$name:$isVideo" }
            override fun openDocument(store: AgentStoreRef, path: String) { opened += "document:${store.storeId}:$path" }
        }
        val store = AgentStoreRef(projectStore, AgentStoreKind.CLOUD)
        val picture = ContextEntry("media/raw_155132.png", isDirectory = false)
        val recording = ContextEntry("media/demo.mp4", isDirectory = false)
        val notes = ContextEntry("notes.md", isDirectory = false)
        val presigned = "https://files.cursor.sh/media/raw_155132.png?X-Amz-Expires=900"

        openStoreFile(store, picture, canReadStores = true, thumbnailUrl = presigned, actions = actions)
        openStoreFile(store, recording, canReadStores = true, thumbnailUrl = null, actions = actions)
        openStoreFile(store, notes, canReadStores = true, thumbnailUrl = null, actions = actions)
        // The demo reads no store: its pictures have only the asset Recents was handed.
        openStoreFile(store, picture, canReadStores = false, thumbnailUrl = presigned, actions = actions)
        openStoreFile(store, picture, canReadStores = false, thumbnailUrl = null, actions = actions)

        assertThat(opened).containsExactly(
            "media:/cursor/stores/$projectStore/media/raw_155132.png:raw_155132.png:false",
            "media:/cursor/stores/$projectStore/media/demo.mp4:demo.mp4:true",
            "document:$projectStore:notes.md",
            "media:$presigned:raw_155132.png:false",
            "document:$projectStore:media/raw_155132.png",
        ).inOrder()
    }
}
