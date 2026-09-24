package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.RunInterruption
import com.cursorforandroid.ui.components.RunStopCopy
import com.cursorforandroid.ui.components.RunStopTags
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Settings › Confirm before stopping, in four frames, dark and light: the question over a running chat once its
 * composer's Stop has been tapped, and the switch in Settings. The chat is held open by the fakes (served through the
 * demo seat of the graph), so the run never finishes under the capture. Same device qualifiers as [AppScreenshotTest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StopConfirmScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val agentId = "bc-stop-frames"
    private val runId = "run-stop-frames-1"
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { Instant.parse(STARTED_AT).toEpochMilli() + 3 * 60_000L }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            appVersion = SCREENSHOT_APP_VERSION,
            demo = CursorBackend(api, streamer, isDemo = true),
        )
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun tool(callId: String, name: String, status: String, vararg args: Pair<String, String>) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = callId, name = name, status = status, args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }),
    )

    /** A chat a few minutes into its run: the prompt, the agent's reads and an edit, a line of its reply, and still working. */
    private fun seedRunningChat() = runBlocking {
        api.addRunningAgent(agentId, "Fix the login redirect loop", runId, createdAt = STARTED_AT)
        api.transcripts[agentId] = listOf(
            V0ConversationMessageDto("$runId-u", "user_message", "Signing in on the web app bounces between the login page and the dashboard forever when the session cookie is stale. Find the loop and fix it without logging anyone out."),
        )
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Thinking("The bounce means both routes redirect on the same stale cookie. Read the middleware and the session check before changing either."))
        streamer.emit(runId, tool("c1", "read_file", "completed", "path" to "web/middleware.ts"))
        streamer.emit(runId, tool("c2", "read_file", "completed", "path" to "web/lib/session.ts"))
        streamer.emit(runId, tool("c3", "grep", "completed", "pattern" to "redirect\\("))
        streamer.emit(runId, RunStreamEvent.Assistant("Found it: the middleware treats an expired cookie as signed in, and the dashboard treats it as signed out. "))
        streamer.emit(runId, tool("c4", "edit_file", "running", "path" to "web/middleware.ts"))
        graph.session.enterDemo()
        graph.agents.refresh()
    }

    private fun dialogFrame(mode: ThemeMode, name: String) {
        seedRunningChat()
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Found it", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(RunStopTags.DIALOG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(RunInterruption.Stop.title).assertIsDisplayed()
        compose.onNodeWithText(RunStopCopy.KEEP_RUNNING).assertIsDisplayed()
        capture(name)
    }

    private fun settingsFrame(mode: ThemeMode, name: String) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SettingsScreen(graph = graph, user = USER, isDemo = false, onOpenSidebar = null, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.CONFIRM_STOP)).fetchSemanticsNodes().isNotEmpty() }
        // Below the New chat page picker: the Chats group is brought up to the top.
        compose.scrollSettingsGroupToTop(SettingsCopy.GROUP_CHATS)
        compose.onNodeWithTag(SettingsTags.CONFIRM_STOP).assertIsDisplayed()
        compose.onNodeWithText(RunStopCopy.SETTING_DETAIL).assertIsDisplayed()
        capture(name)
    }

    @Test
    fun stopConfirmDialogDark() = dialogFrame(ThemeMode.Dark, "143_stop_confirm_dialog_dark")

    @Test
    fun stopConfirmDialogLight() = dialogFrame(ThemeMode.Light, "144_stop_confirm_dialog_light")

    @Test
    fun settingsConfirmStopDark() = settingsFrame(ThemeMode.Dark, "145_settings_confirm_stop_dark")

    @Test
    fun settingsConfirmStopLight() = settingsFrame(ThemeMode.Light, "146_settings_confirm_stop_light")

    private companion object {
        const val STARTED_AT = "2025-01-15T13:57:00.000Z"
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
