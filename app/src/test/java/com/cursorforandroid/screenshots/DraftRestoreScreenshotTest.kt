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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.ModePills
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.conversation.ConversationViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * A chat's follow-up composer reopened in a new process, after Claude Fable 5.1 was picked at 300K context and low
 * effort, the Plan pill put on, and a line typed:
 *
 *  - before (`143`): the draft as 0.3.61 kept it — the text alone, which is all it wrote — restored: the text is back,
 *    the chip has gone back to the chat's model and the pill is gone (Bennett's report);
 *  - after (`144`): the draft as it is kept now, restored: text, pill and model;
 *  - after, the picker (`145`): the model sheet opened on the restored pick, its Context and Effort where they were left.
 *
 * Driven through the real composer over a signed-in account (the demo keeps nothing on disk). Written to
 * `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DraftRestoreScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = object : FakeCursorApi() {
        override suspend fun me() = ApiKeyInfoDto(apiKeyName = "test", userEmail = "bennett@example.com", userId = 7L)
    }
    private val streamer = FakeRunStreamer()
    private val stores = ArrayList<ViewModelStore>()

    @Before
    fun setUp() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        api.modelItems = DemoBackendFactory.create().first.models().items
        api.addFinishedAgent(
            AGENT,
            "Flaky login test",
            Triple("run-1", "The login test fails one run in five. Find out why.", REPLY),
            firstRunAt = "2026-04-13T18:30:00.000Z",
        )
        // The finished turn's log, as the run stream replays it, so its activity reads as settled.
        streamer.emit("run-1", RunStreamEvent.Assistant(REPLY))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, REPLY, 60_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
    }

    @After
    fun tearDown() = runBlocking {
        stores.forEach { it.clear() }
        FollowUpStore(context).clear()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun process(): AppGraph = runBlocking {
        val graph = AppGraph(
            context,
            keyStore = SecureKeyStore(context) { context.getSharedPreferences("draft-restore-keys", Context.MODE_PRIVATE) },
            real = CursorBackend(api, streamer, isDemo = false),
            appVersion = SCREENSHOT_APP_VERSION,
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.agents.refresh()
        graph
    }

    /**
     * The composer as it was before the process ended: the model, the pill and the line, written as the app leaves
     * the screen. Waited on through the rule, which keeps the main looper turning for the view model's work.
     */
    private fun pickAndType(graph: AppGraph) {
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, ConversationViewModel.Factory(graph, AGENT))[ConversationViewModel::class.java]
        runBlocking { graph.catalog.loadModels().getOrThrow() }
        val claude = graph.catalog.models.value.first { it.id == "claude-fable-5.1-thinking" }
        vm.selectModel(claude, claude.variantWithParams(mapOf("context" to "300k", "effort" to "low")))
        vm.setModePill(ModePills.Pill.Plan)
        vm.setDraft(TYPED)
        graph.flushDrafts()
        runBlocking {
            withTimeout(20_000) { while (FollowUpStore(context).read(AGENT)?.draft?.let { it.model != null && it.mode != null && it.text == TYPED } != true) delay(20) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(graph: AppGraph) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, AGENT, onBack = {})
                }
            }
        }
        waitForText("The session cookie is written after the redirect fires")
        waitForText(TYPED)
        // The turn's activity settled, so no frame catches its loading row.
        compose.waitUntil(30_000) { graph.conversations.state(AGENT).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun beforeTheModelRevertedToTheChats() {
        // What 0.3.61 wrote for that composer: the text, and nothing of the pick or the pill.
        val dir = File(File(context.filesDir, "followups"), AGENT).apply { mkdirs() }
        File(dir, "state.json").writeText("""{"draft":{"text":"$TYPED"}}""")

        show(process())
        waitForText("Auto")
        capture("143_draft_restore_before")
    }

    @Test
    fun afterTheWholeComposerComesBack() {
        pickAndType(process())

        show(process())
        waitForText("Claude Fable 5.1")
        waitForText("Plan")
        capture("144_draft_restore_after")

        compose.onNodeWithText("Claude Fable 5.1").performClick()
        waitForText("Effort")
        capture("145_draft_restore_after_picker")
    }

    private companion object {
        const val AGENT = "bc-flaky-login"
        const val TYPED = "Plan the fix before touching the redirect"
        const val REPLY = "The session cookie is written after the redirect fires; the test reads it before it lands."
        val FIXED_NOW = java.time.Instant.parse("2026-04-13T19:10:00Z").toEpochMilli()
    }
}
