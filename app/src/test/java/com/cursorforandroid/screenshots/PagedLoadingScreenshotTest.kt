package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OlderTurnsRow
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A long chat opened on its newest window, with the turns before it being paged in: the "Loading older…" row past
 * the oldest turn shown, and the same row at rest, a tap away, once the reader's scroll stopped short of asking.
 * Written to `screenshots/` beside the walkthrough; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PagedLoadingScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    /** The oldest turn of the window: what sits right under the row, with a settled trace of its own. */
    private fun window(): List<TimelineItem> {
        fun call(id: String, kind: ToolKind, name: String, summary: String, detail: String?) =
            ToolCall(id, name, kind, ToolCall.STATUS_COMPLETED, summary, detail = detail)
        return listOf(
            UserMessage("u41", "Wire the paged transcript into the conversation screen and keep the composer where it is.", timestampMillis = 1_736_949_600_000),
            ActivityGroup(
                "g41",
                listOf(
                    ThinkingBlock("The list is bottom-anchored, so the older turns belong past its last index.", durationSeconds = 6),
                    call("r41", ToolKind.Read, "read_file", "ConversationScreen.kt", "app/src/main/java/com/cursorforandroid/ui/conversation/ConversationScreen.kt"),
                    call("r42", ToolKind.Read, "read_file", "ConversationRepository.kt", "app/src/main/java/com/cursorforandroid/data/repo/ConversationRepository.kt"),
                    call("g41", ToolKind.Grep, "grep", "reverseLayout in conversation", "reverseLayout in app/src/main/java/com/cursorforandroid/ui/conversation"),
                    call("e41", ToolKind.Edit, "edit_file", "ConversationScreen.kt", "app/src/main/java/com/cursorforandroid/ui/conversation/ConversationScreen.kt"),
                ),
            ),
            AssistantMessage("a41", "The older turns now page in as you scroll up; the window opens on the newest ten runs."),
            RunFooter("f41", "run-41", RunStatus.FINISHED, 184_000, emptyList()),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Transcript(loading: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember { MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) }
        val lightbox = rememberLightboxState("bc-demo")
        val media = remember(loader, lightbox) { MarkdownMediaContext("bc-demo", loader, lightbox) }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media, LocalTranscriptControls provides TranscriptControls()) {
                Column(
                    Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OlderTurnsRow(isLoading = loading, onLoad = {})
                    window().forEach { TimelineItemView(it) }
                }
            }
        }
    }

    @Test
    fun transcriptLoadingOlder() {
        compose.setContent { Transcript(loading = true) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("loading-older")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "58_transcript_loading_older.png").path, RoborazziOptions())
    }

    @Test
    fun transcriptOlderMessagesAtRest() {
        compose.setContent { Transcript(loading = false) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("load-older")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "59_transcript_older_messages.png").path, RoborazziOptions())
    }
}
