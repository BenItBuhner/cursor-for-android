package com.cursorforandroid.screenshots

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * A chat's header under a 24dp status bar, in the demo's chat with a pull request and in its Project's coordinator
 * chat, dark and light: back, the pull request where there is one, the panel and the menu in a 40dp row, and the
 * transcript from right under it; and the chat's menu open, dark. Same device qualifiers as [AppScreenshotTest];
 * written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ChatHeaderScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode, agentName: String? = null, agentId: String? = null): Pair<AppGraph, String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        val id = agentId ?: graph.agents.state.value.agents.first { it.name == agentName }.id
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, id, onBack = {})
                }
            }
        }
        dispatchStatusBar()
        return graph to id
    }

    private fun dispatchStatusBar() {
        val px = (STATUS_BAR_DP * compose.activity.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** The finished run's trace spliced in, by the frame's word (see [AppScreenshotTest.tabletTwoPane]). */
    private fun chat(mode: ThemeMode, name: String, menuOpen: Boolean = false) {
        val (graph, id) = show(mode, agentName = "Revenue Scaling Pipeline Research")
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(id).value
            state.items.any { it is ActivityGroup } && state.traceStatus.pending == 0 && !onScreen("Loading the activity") && onScreen("1 thought")
        }
        compose.waitForIdle()
        if (menuOpen) {
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitForIdle()
        }
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The coordinator's word to the user on screen and both turns' stretches drawn (see [AppScreenshotTest.projectView]). */
    private fun project(mode: ThemeMode, name: String) {
        val (graph, id) = show(mode, agentId = DemoData.PROJECT_ID)
        compose.waitUntil(60_000) { onScreen("PR #215 (usage aggregation) is") }
        compose.waitUntil(60_000) { graph.conversations.state(id).value.let { state -> state.items.count { it is ActivityGroup } >= 2 && state.traceStatus.pending == 0 } }
        compose.waitUntil(30_000) { !onScreen("Loading the activity") }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun chatDark() = chat(ThemeMode.Dark, "127_slim_header_chat_dark")

    @Test
    fun chatLight() = chat(ThemeMode.Light, "128_slim_header_chat_light")

    @Test
    fun chatMenuDark() = chat(ThemeMode.Dark, "196_chat_menu_dark", menuOpen = true)

    @Test
    fun projectDark() = project(ThemeMode.Dark, "129_slim_header_project_dark")

    @Test
    fun projectLight() = project(ThemeMode.Light, "130_slim_header_project_light")

    private companion object {
        /** The reference device's status bar at 420dpi. */
        const val STATUS_BAR_DP = 24

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
