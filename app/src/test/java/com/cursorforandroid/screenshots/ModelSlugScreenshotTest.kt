package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Bennett's frame (v0.3.78): a Project worker whose coordinator started it on `claude-opus-5-5-max-fast`. The chip
 * read the slug; it reads the catalogue's name now (`197`), and the picker opens on Claude Opus 5.5 with Effort on Max
 * and Fast on (`198`), as a hand pick leaves it. Driven through the real screen over a catalogue shaped like the live
 * one ([LiveModelCatalog]). Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ModelSlugScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = object : FakeCursorApi() {
        override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test", userEmail = "bennett@example.com", userId = 7L)
    }
    private val streamer = FakeRunStreamer()

    @Before
    fun setUp() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        api.modelItems = LiveModelCatalog.items
        api.addFinishedAgent(PROJECT, "Narration benchmark", Triple("run-p", "Benchmark our narration against the comparison channels.", "Workers started."), firstRunAt = "2026-09-23T06:00:00.000Z")
        api.addFinishedAgent(WORKER, "Evidence frames", Triple("run-w", PROMPT, REPLY), firstRunAt = "2026-09-23T06:10:00.000Z")
        for ((run, reply) in listOf("run-p" to "Workers started.", "run-w" to REPLY)) {
            streamer.emit(run, RunStreamEvent.Assistant(reply))
            streamer.emit(run, RunStreamEvent.Result(run, RunStatus.FINISHED, reply, 60_000, null))
            streamer.emit(run, RunStreamEvent.Done)
        }
    }

    @After
    fun tearDown() = runBlocking {
        FollowUpStore(context).clear()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun process(): AppGraph = runBlocking {
        val graph = AppGraph(
            context,
            keyStore = SecureKeyStore(context) { context.getSharedPreferences("model-slug-keys", Context.MODE_PRIVATE) },
            real = CursorBackend(api, streamer, isDemo = false),
            appVersion = SCREENSHOT_APP_VERSION,
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.agents.refresh()
        // The account's records (Extended mode): the Project, and the worker its coordinator started on the slug.
        graph.agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot(PROJECT, isProject = true, record = RecordFields(projectMetadata = "{}"), model = AccountModel("claude-opus-5-5-xhigh")),
                ComposerSnapshot(
                    WORKER,
                    record = RecordFields(managerAgentId = PROJECT),
                    parent = AgentParent(PROJECT, AgentParentKind.PROJECT_WORKER),
                    model = AccountModel("claude-opus-5-5-max-fast"),
                ),
            ),
        )
        graph
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(graph: AppGraph) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, WORKER, onBack = {})
                }
            }
        }
        waitForText(REPLY)
        compose.waitUntil(30_000) { graph.conversations.state(WORKER).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun aProjectWorkersInheritedSlugReadsAsTheModel() {
        show(process())
        waitForText("Claude Opus 5.5")
        capture("197_model_slug_worker_chip")

        compose.onNodeWithText("Claude Opus 5.5").performClick()
        waitForText("Effort")
        capture("198_model_slug_worker_picker")
    }

    private companion object {
        const val PROJECT = "bc-narration"
        const val WORKER = "bc-evidence-frames"
        const val PROMPT = "Pull our evidence frames at full resolution and OCR the cut and chapter badges."
        const val REPLY = "Corrections applied. Checking the watermark crop and the 1-second grid before publishing."
        val FIXED_NOW = java.time.Instant.parse("2026-09-23T08:46:00Z").toEpochMilli()
    }
}
