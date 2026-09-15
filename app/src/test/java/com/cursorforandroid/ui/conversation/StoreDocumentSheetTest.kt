package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * A store document tapped in a chat (the coordinator's `docs/project-ui-parity-spec.md` link, see
 * `store_paths_message.json`) opens in the sheet: rendered as markdown first, its source a tap away, read through the
 * account like the panel's Context browser; a failure says why and offers a retry; the header opens the Project on the web.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StoreDocumentSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val document = StorePath.parse(fixture.getValue("documentPath").jsonPrimitive.content)!!

    private class Store : AgentStoreApi {
        val reads = mutableListOf<String>()
        var failing: Throwable? = null
        var text = "# Project UI parity\n\nThe panel is **tabbed**: Files, Changes, Context.\n\n- Rail\n- Pane"
        override suspend fun storeFor(sourceId: String): String? = "st-proj"
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String {
            reads += "$storeId:$relativePath"
            failing?.let { throw it }
            return text
        }
        override suspend fun presignRead(requesterId: String, storeId: String, relativePath: String): PresignedStoreRead? = null
    }

    private val api = Store()
    private val opened = mutableListOf<String>()

    private fun show(path: StorePath = document, chat: String = store) {
        val files = StoreFileRepository(api = { api }, capabilities = { Capabilities.EXTENDED })
        val ref = storeRef(path, chat)!!
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                    StoreDocumentSheet(ref, files, onDismiss = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(path.fileName)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a markdown document opens rendered, with its source a tap away, read once through the account`() {
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("store-document-preview")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project UI parity").assertIsDisplayed()
        compose.onNodeWithText(document.text).assertIsDisplayed()
        assertThat(api.reads).containsExactly("st-proj:docs/project-ui-parity-spec.md")

        compose.onNodeWithTag("store-source").performClick()
        compose.onNodeWithTag("store-document-source").assertIsDisplayed()
        compose.onNodeWithText(api.text).assertIsDisplayed()
        compose.onNodeWithTag("store-preview").performClick()
        compose.onNodeWithTag("store-document-preview").assertIsDisplayed()
        // The source was the same text, not another read.
        assertThat(api.reads).hasSize(1)

        compose.onNodeWithTag("store-open-web").performClick()
        assertThat(opened).containsExactly("https://cursor.com/agents/$store")
    }

    @Test
    fun `a document the store will not give says why, and a retry asks again`() {
        api.failing = IOException("The store is not reachable from here.")
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("store-document-error")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The store is not reachable from here.").assertIsDisplayed()

        api.failing = null
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("store-document-preview")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(api.reads).hasSize(2)
    }

    @Test
    fun `a plain text file has only its source, and self reads as the chat's own store`() {
        api.text = "worker=bc-1\nbranch=main"
        show(StorePath.parse("/cursor/stores/self/internal/state.txt")!!, chat = "bc-worker")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("store-document-source")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("worker=bc-1\nbranch=main").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("store-preview")).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
        assertThat(storeRef(StorePath.parse("/cursor/stores/self/internal/state.txt")!!, "bc-worker")).isEqualTo(MediaRef.Store("bc-worker", "internal/state.txt", "bc-worker"))
        assertThat(storeRef(StorePath.parse("/cursor/stores/user/notes.md")!!, "bc-worker")).isNull()
        assertThat(api.reads).containsExactly("st-proj:internal/state.txt")
    }
}
