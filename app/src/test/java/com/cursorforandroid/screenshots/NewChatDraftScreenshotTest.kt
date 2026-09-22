package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * A draft opened from the sidebar in the New Chat pane with everything it was written with: the repository and branch,
 * the team pool it would run on, Claude Fable 5.1 on the chip and the Plan pill (`187`), and the model sheet on its
 * 300K context and low effort (`188`). The demo's data around it; the clock pinned like the walkthrough's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatDraftScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: AppGraph

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun aDraftOpensWhole() {
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.drafts.write(
                DraftStore.Record(
                    id = DRAFT,
                    createdAtMillis = FIXED_NOW - 5 * 60_000L,
                    updatedAtMillis = FIXED_NOW - 3 * 60_000L,
                    prompt = PROMPT,
                    repoUrl = "https://github.com/bennett/cursor-for-android",
                    ref = "cursor/browser-login-copy",
                    device = DeviceTarget.pool("gpu"),
                    modelId = "claude-fable-5.1-thinking",
                    modelParams = listOf(ModelParam("context", "300k"), ModelParam("effort", "low")),
                    modelLabel = "Claude Fable 5.1",
                    modelChosen = true,
                    planMode = true,
                    nonce = "n-screenshot",
                ),
            )
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = false,
                        deepLinkAgentId = null,
                        onDeepLinkConsumed = {},
                    )
                }
            }
        }
        compose.waitUntil(30_000) { graph.newChatDrafts.state.value.loaded && graph.newChatDrafts.record(DRAFT) != null }
        waitForText("Ask Cursor to build, fix bugs, explore")
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }

        // The sidebar's row, tapped: the pane opens it.
        graph.newChatDrafts.request(NewChatDrafts.Request.Open(DRAFT))
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and hasText(PROMPT)).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Claude Fable 5.1")
        waitForText("cursor/browser-login-copy")
        waitForText("gpu")
        waitForText("Cesium Revenue Strategy")
        capture("187_new_chat_draft_reopened")

        compose.onNodeWithText("Claude Fable 5.1").performClick()
        waitForText("Effort")
        capture("188_new_chat_draft_reopened_picker")
    }

    private companion object {
        const val DRAFT = "draft-screenshot"
        const val PROMPT = "Rewrite the sign-in screen's copy for the browser login"
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
