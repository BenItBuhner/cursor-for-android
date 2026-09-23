package com.cursorforandroid.ui.files

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every tap target in a file-edit tool-call row opens the full-file viewer at the edit's changed lines — the path
 * link, and the diff card's header — and none of them, not even a store-file edit in default mode, leaves for a
 * cursor.com URL. Bennett's report on the current release: tapping the diff opened the Cursor website. The web
 * hand-off was the store-path fallback (`ConversationScreen.onOpenStorePath`), reached from an edit row through the
 * store branch of `rememberFileOpener`; only an explicit "Open in browser" choice may leave the app.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class EditDiffTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<FileOpenRequest>()
    private val storeOpened = mutableListOf<String>()
    private val urisOpened = mutableListOf<String>()
    private val viewer = MediaViewerState(null)
    private val uris = object : UriHandler {
        override fun openUri(uri: String) {
            urisOpened += uri
        }
    }

    private val path = "/workspace/app/src/main/java/com/cursorforandroid/data/api/AgentFilesApi.kt"
    private val diff = "@@ -14,3 +14,5 @@\n     }\n \n+    fun added() = Unit\n+\n     fun tail() = Unit\n"
    private fun edit(p: String = path) = ToolCall(
        "c-edit", "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, ToolNamesBasename(p), detail = p, linesAdded = 2, linesRemoved = 0,
        payload = ToolPayload.FileDiff(p, diff, linesAdded = 2, linesRemoved = 0),
    )

    private fun ToolNamesBasename(p: String) = p.substringAfterLast('/')

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Rows(call: ToolCall, canReadStores: Boolean = false) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides null,
                LocalUriHandler provides uris,
                LocalTranscriptControls provides TranscriptControls(onOpenFile = { opened += it }),
                LocalMediaViewer provides viewer,
                LocalMarkdownMedia provides MarkdownMediaContext("bc-edit", ViewerFixtures.loader(), canReadStores = canReadStores, onOpenStorePath = { storeOpened += it.text }),
            ) {
                Column { ToolCallLine(call) }
            }
        }
    }

    private fun expandRow(verb: String) {
        // The verb is the row's own toggle; tapping it opens the diff card, and opens no file.
        compose.onNodeWithText(verb).performTouchInput { click(centerLeft + Offset(8f, 0f)) }
        compose.waitForIdle()
    }

    @Test
    fun `the path link opens the full-file viewer at the edit, not a browser`() {
        compose.setContent { Rows(edit()) }
        compose.onNodeWithTag("file-link").performClick()
        assertThat(opened).containsExactly(FileOpenRequest(path, "c-edit"))
        assertThat(urisOpened).isEmpty()
        assertThat(storeOpened).isEmpty()
    }

    @Test
    fun `the diff card header opens the full-file viewer at the edit, not a browser`() {
        compose.setContent { Rows(edit()) }
        expandRow("Edited")
        compose.onNodeWithTag("payload-open").performClick()
        assertThat(opened).containsExactly(FileOpenRequest(path, "c-edit"))
        assertThat(urisOpened).isEmpty()
    }

    @Test
    fun `the diff body and its line counts are shown, and tapping them leaves nothing to the browser`() {
        compose.setContent { Rows(edit()) }
        expandRow("Edited")
        // The diff renders its lines; the body and the +N -M counts are not web links.
        compose.onNodeWithText("fun added() = Unit", substring = true).assertExists()
        compose.onNodeWithTag("diff-block").performClick()
        assertThat(urisOpened).isEmpty()
        assertThat(storeOpened).isEmpty()
    }

    @Test
    fun `a store-file edit opens the full-file viewer, never the store sheet or cursor_com, in default mode`() {
        val storePath = "/cursor/stores/bc-proj/docs/plan.md"
        // A markdown store file so it is not routed to the media viewer.
        compose.setContent { Rows(edit(storePath), canReadStores = false) }
        assertThat(StorePath.parse(storePath)).isNotNull()
        compose.onNodeWithTag("file-link").performClick()
        assertThat(opened).containsExactly(FileOpenRequest(storePath, "c-edit"))
        assertThat(urisOpened).isEmpty()
        assertThat(storeOpened).isEmpty()
    }

    @Test
    fun `a store-file edit opens the full-file viewer even when the store is readable, so the changed lines show`() {
        val storePath = "/cursor/stores/bc-proj/docs/plan.md"
        compose.setContent { Rows(edit(storePath), canReadStores = true) }
        compose.onNodeWithTag("file-link").performClick()
        // An edit is the full-file viewer's, not the store sheet's: the sheet shows the current doc, not the diff.
        assertThat(opened).containsExactly(FileOpenRequest(storePath, "c-edit"))
        assertThat(storeOpened).isEmpty()
        assertThat(urisOpened).isEmpty()
    }
}
