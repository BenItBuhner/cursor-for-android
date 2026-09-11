package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.conversation.TimelineItemView
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
 * A transcript carrying what the documented stream's `interaction_update` event delivers: an edit opened onto its
 * diff, a file the agent read behind its line, an image it generated under the group, and the question it is
 * waiting on. Written to `screenshots/` beside the walkthrough; CI compares it pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RichContentScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    /** A picture the "generated image" stands in with: a swatch the app's own code path writes and reads as a file. */
    private fun generatedImage(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(0x1E, 0x2A, 0x3A))
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = AndroidColor.rgb(0xF5, 0x8A, 0x3E)
        canvas.drawCircle(96f, 90f, 54f, paint)
        paint.color = AndroidColor.rgb(0x5B, 0xC0, 0xBE)
        canvas.drawRoundRect(170f, 40f, 290f, 140f, 18f, 18f, paint)
        val file = File(context.filesDir, "generated/bc-demo/call-img.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private fun items(imageSrc: String): List<TimelineItem> {
        val diff = ToolPayload.FileDiff(
            path = "app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt",
            diff = """
                @@ -212,7 +212,9 @@ fun AppearanceSection(prefs: PreferencesStore) {
                     SettingRow("Theme", mode.label) { themeSheet = true }
                -    if (mode == ThemeMode.Dark) {
                -        ToggleRow("OLED black", oledBlack, prefs::setOledBlack)
                +    // The OLED row only makes sense on a dark surface; hide it rather than grey it out.
                +    if (mode != ThemeMode.Light) {
                +        ToggleRow("OLED black", oledBlack, prefs::setOledBlack)
                +        ToggleRow("Dim wallpaper", dimWallpaper, prefs::setDimWallpaper)
                     }
                 }
            """.trimIndent(),
            linesAdded = 5,
            linesRemoved = 2,
        )
        val read = ToolPayload.FileContent(
            path = "app/src/main/java/com/cursorforandroid/ui/theme/CursorTheme.kt",
            content = "@Composable\nfun CursorTheme(mode: ThemeMode, oledBlack: Boolean = false, content: @Composable () -> Unit) {\n    val colors = when (mode) {\n        ThemeMode.Light -> LightColors\n        else -> if (oledBlack) OledColors else DarkColors\n    }",
            kind = ToolPayload.FileContent.Kind.Read,
            totalLines = 141,
            fileSize = 5_812,
        )
        val image = ToolPayload.GeneratedImage(path = "/workspace/docs/theme-toggle.png", description = "Settings row with the new theme toggle", src = imageSrc)
        val question = ToolPayload.Question(
            title = "Before I wire the toggle",
            questions = listOf(
                ToolPayload.Question.Item(
                    id = "q1",
                    prompt = "Should the OLED black option also apply to the widget?",
                    options = listOf(ToolPayload.Question.Option("a", "Yes, match the app"), ToolPayload.Question.Option("b", "No, keep the widget as is")),
                ),
            ),
        )
        fun call(id: String, kind: ToolKind, name: String, summary: String, detail: String?, payload: ToolPayload?, status: String = ToolCall.STATUS_COMPLETED, added: Int? = null, removed: Int? = null) =
            ToolCall(id, name, kind, status, summary, detail = detail, payload = payload, linesAdded = added, linesRemoved = removed)
        return listOf(
            UserMessage("u1", "Add a dark theme toggle to the settings panel and show me what it looks like.", timestampMillis = 1_736_949_600_000),
            ActivityGroup(
                "g1",
                listOf(
                    ThinkingBlock("The appearance section already has a theme row; the toggle belongs beside it.", durationSeconds = 4),
                    call("r1", ToolKind.Read, "read_file", "CursorTheme.kt", read.path, read),
                    call("e1", ToolKind.Edit, "edit_file", "SettingsScreen.kt", diff.path, diff, added = 5, removed = 2),
                    call("i1", ToolKind.Image, "generate_image", "Settings row with the new theme toggle", "Settings row with the new theme toggle", image),
                ),
            ),
            AssistantMessage("a1", "The toggle is in. One question before I touch the widget:"),
            ActivityGroup("g2", listOf(call("q1", ToolKind.Question, "ask_question", "", null, question, status = ToolCall.STATUS_RUNNING))),
        )
    }

    private class StubApi : FakeCursorApi() {
        override suspend fun artifactUrl(id: String, path: String) = DownloadArtifactResponseDto(url = "file:///nowhere/$path")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Transcript(items: List<TimelineItem>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember { MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { StubApi() })) }
        val lightbox = rememberLightboxState("bc-demo")
        val media = remember(loader, lightbox) { MarkdownMediaContext("bc-demo", loader, lightbox) }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media) {
                Column(
                    Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items.forEach { TimelineItemView(it) }
                }
            }
        }
    }

    @Test
    fun transcriptRichContent() {
        val scene = items(generatedImage())
        compose.setContent { Transcript(scene) }

        // Open the work onto its steps, then the edit onto its diff; the read stays a line, the picture and the
        // question are on screen without opening anything.
        compose.onNodeWithText("Edited").performClick()
        // The summary row's verb and the edit's own line both read "Edited" once the work is open.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Edited")).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText("Edited")[1].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("diff-block")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Settings row with the new theme toggle")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "38_transcript_rich_content.png").path, RoborazziOptions())
    }
}
