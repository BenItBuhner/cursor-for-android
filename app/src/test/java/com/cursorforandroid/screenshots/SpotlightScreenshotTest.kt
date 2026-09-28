package com.cursorforandroid.screenshots

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SpotlightView
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.notifications.SpotlightRenderer
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * Spotlight (740 on): a running chat's long-press menu offering it, in both themes, and the same menu while that chat
 * is in the Spotlight offering to stop it; then the notification itself as the platform draws it, collapsed (the name
 * and the step) and expanded (the subagents under the step and the tally), a Project's, and the expanded card in the
 * light theme. The notification's chronometer is pinned, so the frames never depend on the wall clock. Written to
 * `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SpotlightScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
        LiveNotifications.ensureChannels(context)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(mode: ThemeMode, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - (ageMinutes + 40) * 60_000L,
        updatedAtMillis = NOW - ageMinutes * 60_000L,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = if (branch != null) listOf(GitBranch("github.com/acme/app", branch, null)) else emptyList(),
    )

    private fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read) =
        AgentRow(agent = agent, indicator = indicator, isPinned = false, isUnread = false, launchedFromThisDevice = false, children = emptyList())

    private val rows = listOf(
        row(agent("limbs", "Hyper-realistic human limbs", 1, branch = "cursor/limbs")),
        row(agent("cesium", "Cesium Revenue Strategy", 3, running = true), indicator = AgentIndicator.Running),
        row(agent("codex", "Codex-Poly-Bot Scaling", 34, running = true), indicator = AgentIndicator.Running),
        row(agent("cli", "Cli exploration", 19, branch = "cursor/cli")),
    )

    private fun menu(mode: ThemeMode, name: String, spotlighted: String? = null) {
        val actions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, onSpotlight = {}, spotlightedId = spotlighted)
        compose.setContent {
            Scene(mode) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    rows.forEach { row -> AgentRowItem(row, selected = false, prefs = ListPreferences(), actions = actions, nowMillis = NOW) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Cesium Revenue Strategy").performTouchInput { longClick() }
        capture(name)
    }

    @Test
    fun menuDark() = menu(ThemeMode.Dark, "740_spotlight_menu_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun menuLight() = menu(ThemeMode.Light, "741_spotlight_menu_light")

    @Test
    fun stopMenuDark() = menu(ThemeMode.Dark, "742_spotlight_menu_stop_dark", spotlighted = "cesium")

    private val subagents = SpotlightView(
        agentId = "cesium",
        title = "Cesium Revenue Strategy",
        step = "Delegating to 3 subagents",
        lines = listOf(
            "\u2022 Survey cloud sync layer",
            "\u2022 Survey first-party agent and inference",
            "\u2022 Survey distribution and platform surfaces",
        ),
        tally = "12 tool calls",
        startedAtMillis = NOW,
    )

    private val project = SpotlightView(
        agentId = "billing",
        title = "Cesium billing launch",
        step = "Reading handoff/cesium.md",
        lines = listOf(
            "\u2022 Stripe webhook handler \u00B7 Editing webhooks.ts",
            "\u2022 Usage events aggregation \u00B7 Running tests",
        ),
        tally = "2 of 5 chats running",
        startedAtMillis = NOW,
        isProject = true,
    )

    /**
     * The card as the shade draws it: the platform's template for [view], [expanded] or not, on a panel the shade's
     * colour, with the chronometer held at four minutes twelve.
     */
    private fun notification(view: SpotlightView, expanded: Boolean, name: String, night: Boolean = true) {
        val n = SpotlightRenderer.spotlight(context, view)
        val builder = Notification.Builder.recoverBuilder(context, n)
        val remote = if (expanded) builder.createBigContentView() else builder.createContentView()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val panel = FrameLayout(activity).apply {
            val inset = (16 * density).toInt()
            setPadding(inset, inset, inset, inset)
            setBackgroundColor(if (night) 0xFF101114.toInt() else 0xFFE9E9EF.toInt())
        }
        val card = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(if (night) 0xFF2A2C31.toInt() else 0xFFFFFFFF.toInt())
            }
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        card.addView(remote.apply(activity, card), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(LinearLayout(activity).apply { addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        card.findChronometer()?.apply {
            stop()
            base = SystemClock.elapsedRealtime() - (4 * 60 + 12) * 1_000L
        }
        panel.captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun View.findChronometer(): Chronometer? = when (this) {
        is Chronometer -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findChronometer() }
        else -> null
    }

    @Test
    fun collapsed() = notification(subagents, expanded = false, name = "743_spotlight_notification_collapsed")

    @Test
    fun expanded() = notification(subagents, expanded = true, name = "744_spotlight_notification_expanded")

    @Test
    fun projectExpanded() = notification(project, expanded = true, name = "745_spotlight_notification_project")

    @Test
    @Config(qualifiers = LIGHT)
    fun expandedLight() = notification(subagents, expanded = true, name = "746_spotlight_notification_expanded_light", night = false)

    private companion object {
        const val LIGHT = "w411dp-h914dp-notnight-420dpi"

        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; ages read against it. */
        const val NOW = 1_736_949_600_000L
    }
}
