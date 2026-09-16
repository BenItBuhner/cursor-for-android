package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.MediaSegment
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A coordinator's update pointing into the Project's Agent Store (see [CoordinatorFixtures], `store_paths_message.json`:
 * the image line Bennett quoted, the coordinator's own link and code-span shapes, a recording). In Extended mode the
 * picture is fetched through the account's presigned read and drawn inline, the documents are links, the recording a
 * player; without the account each is a card that opens the Project on cursor.com — where "Image isn't available"
 * used to be. Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StoreMediaScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val markdown = fixture.getValue("markdown").jsonPrimitive.content
    private val server = MockWebServer()

    /** The account's store reads: the Project's store, its picture behind a presigned URL served here. */
    private inner class StoreOnTheWire : AgentStoreApi {
        override suspend fun storeFor(sourceId: String): String? = "st-proj".takeIf { sourceId == store }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = "# $relativePath"
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String) =
            PresignedStoreRead(relativePath, server.url("/signed/$relativePath").toString(), null)
    }

    @After
    fun tearDown() = server.shutdown()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The turn: Bennett's report, the coordinator's update carrying the store references, the run's footer. */
    private fun turn(): List<TranscriptRow> {
        val items: List<TimelineItem> = listOf(
            UserMessage("u1", "Where are the landscape frames and the spec for the tabbed panel?", timestampMillis = 1_789_350_000_000L),
            ActivityGroup(
                "g1",
                listOf(
                    ToolCall("c1", ToolNames.USER_MESSAGE_TOOL, ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(markdown)),
                ),
            ),
            RunFooter("run-1", "run-1", RunStatus.FINISHED, 12_000, emptyList()),
        )
        assertThat(CoordinatorTranscript.hasCoordinatorContent(items)).isTrue()
        return TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Transcript(rows: List<TranscriptRow>, capabilities: Capabilities) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember {
            val files = StoreFileRepository(api = { StoreOnTheWire() }, capabilities = { capabilities }, http = OkHttpClient())
            MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) { files }
        }
        val lightbox = rememberLightboxState(store)
        val media = remember(loader, lightbox) { MarkdownMediaContext(store, loader, lightbox, canReadStores = capabilities.projects, onOpenStorePath = {}) }
        val controls = TranscriptControls(coordinatorMode = true)
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media, LocalTranscriptControls provides controls) {
                Column(
                    Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rows.forEach { TranscriptRowView(it) }
                }
            }
        }
    }

    /** Extended mode: the store's picture inline, from the presigned read; the documents as links; the recording as a player. */
    @Test
    fun transcriptStoreImagesExtended() {
        val bytes = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()
        // By path, since the two figures are fetched at once: the frame's bytes; the board under `self` is not in
        // the store, so its URL is dead, asked for again once, then a card that still opens the Project.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/signed/media/ui-parity/")) MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                else MockResponse().setResponseCode(404)
        }
        val rows = turn()
        // The update is the message, and its media are what the fixture says they are.
        val message = rows.filterIsInstance<TranscriptRow.Message>().single()
        val media = MediaMarkup.split((message.call.payload as ToolPayload.CoordinatorMessage).message)
        assertThat(media.filterIsInstance<MediaSegment.Image>().map { StorePath.parse(it.src)!!.fileName }).containsExactly("tab-landscape-icon-only-project.png", "board.png").inOrder()
        assertThat(media.filterIsInstance<MediaSegment.Video>()).hasSize(1)

        compose.setContent { Transcript(rows, Capabilities.EXTENDED) }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Tab layout, landscape, icon-only rail, Project view")).fetchSemanticsNodes().size == 1 }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("store-file-card")).fetchSemanticsNodes().size == 1 }
        // The picture came through the presigned read once; the missing board was asked for twice and given up on.
        assertThat(server.requestCount).isEqualTo(3)
        capture("70_transcript_store_images")
    }

    /** Default mode: no store read exists, so each store reference is a card that opens the Project on cursor.com. */
    @Test
    fun transcriptStoreImagesDefault() {
        compose.setContent { Transcript(turn(), Capabilities.DOCUMENTED) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("store-file-card")).fetchSemanticsNodes().size == 3 }
        assertThat(server.requestCount).isEqualTo(0)
        capture("71_transcript_store_images_default")
    }
}
