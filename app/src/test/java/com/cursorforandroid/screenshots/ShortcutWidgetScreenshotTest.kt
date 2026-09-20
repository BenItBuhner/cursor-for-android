package com.cursorforandroid.screenshots

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.compose.NewAgentUiState
import com.cursorforandroid.ui.quick.QuickComposerSheet
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.widget.ShortcutChoices
import com.cursorforandroid.widget.ShortcutConfig
import com.cursorforandroid.widget.ShortcutVariant
import com.cursorforandroid.widget.ShortcutWidgetConfigureScreen
import com.cursorforandroid.widget.ShortcutWidgetContent
import com.cursorforandroid.widget.WidgetData
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * The shortcut widgets the way a launcher draws them — the Glance composition to RemoteViews, the RemoteViews to
 * views on a wallpaper-coloured host — in each look and shape, then the quick composer over the launcher and the
 * widget's configuration screen. Same pinned clock, zone and locale as [WidgetScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ShortcutWidgetScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

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

    /** The button in its four looks, each on a 1x1 cell over a wallpaper-like ground. */
    @Test
    fun buttons() {
        captureWidget("101_shortcut_widget_white", ShortcutConfig(ShortcutStyle.White), ShortcutVariant.Button, CELL)
        captureWidget("102_shortcut_widget_tinted", ShortcutConfig(ShortcutStyle.Tinted), ShortcutVariant.Button, CELL)
        captureWidget("103_shortcut_widget_glass", ShortcutConfig(ShortcutStyle.Glass), ShortcutVariant.Button, CELL)
        captureWidget("104_shortcut_widget_icon_only", ShortcutConfig(ShortcutStyle.IconOnly), ShortcutVariant.Button, CELL)
        // Pointed at a Project, the button wears the cube.
        captureWidget("105_shortcut_widget_project", ShortcutConfig(ShortcutStyle.White, ShortcutTarget.Project("bc-1", "Cursor for Android")), ShortcutVariant.Button, CELL)
    }

    /** The compose bar across four cells, and the composer box it becomes two rows tall. */
    @Test
    fun bars() {
        captureWidget("106_compose_bar_widget", ShortcutConfig(ShortcutStyle.White), ShortcutVariant.Bar, DpSize(320.dp, 72.dp))
        captureWidget("107_compose_bar_widget_tinted", ShortcutConfig(ShortcutStyle.Tinted), ShortcutVariant.Bar, DpSize(320.dp, 72.dp))
        captureWidget("108_compose_bar_widget_tall", ShortcutConfig(ShortcutStyle.Tinted), ShortcutVariant.TallBar, DpSize(320.dp, 140.dp))
    }

    /** The quick composer over the launcher: as it opens, with a draft and an image, and with a refusal under it. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun quickComposer() {
        val base = NewAgentUiState(
            repositories = listOf(Repository("https://github.com/BenItBuhner/cursor-for-android")),
            selectedRepo = Repository("https://github.com/BenItBuhner/cursor-for-android"),
            models = listOf(ModelOption(AccountModel.AUTO_ID, AccountModel.AUTO_LABEL), ModelOption("claude-fable-5.1", "Claude Fable 5.1")),
            selectedModel = ModelOption("claude-fable-5.1", "Claude Fable 5.1"),
        )
        val drafted = base.copy(
            prompt = "Add a home-screen shortcut widget that opens the composer over the launcher",
            attachments = listOf(screenshot()),
        )
        // One composition, moved between scenes: the rule allows a single setContent per test.
        val scene = mutableStateOf(base to false)
        showSheet(scene)
        capture("109_quick_composer")
        scene.value = drafted to true
        capture("110_quick_composer_drafted")
        scene.value = drafted.copy(error = "Cannot start Cloud Agent: the repository is not connected to Cursor's GitHub app.") to true
        capture("111_quick_composer_refused")
    }

    /** The screen the launcher opens when a shortcut widget is placed. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun configure() {
        val sample = WidgetData.sample(ThemeMode.Dark, FIXED_NOW)
        val sections = AgentListOrganizer.organize(sample.agents, ListPreferences(), LocalAgentState(pinnedIds = setOf("bc-preview-1", "bc-preview-3")), nowMillis = FIXED_NOW)
        val choices = ShortcutChoices(pinned = sections.firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows.orEmpty())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ShortcutWidgetConfigureScreen(config = ShortcutConfig(ShortcutStyle.White, ShortcutTarget.NewChat), choices = choices, onStyle = {}, onTarget = {}, onDone = {}, onClose = {})
                }
            }
        }
        capture("112_shortcut_widget_configure")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSheet(scene: MutableState<Pair<NewAgentUiState, Boolean>>) {
        compose.setContent {
            val (state, expanded) = scene.value
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(Wallpaper)) {
                        QuickComposerSheet(
                            state = state,
                            commands = SlashCatalog.BUILT_IN,
                            plusMenu = null,
                            contextExpanded = expanded,
                            onExpandContext = {},
                            onDismiss = {},
                            onCancel = {},
                            onSend = {},
                            onCancelSend = {},
                            onPrompt = {},
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun captureWidget(name: String, config: ShortcutConfig, variant: ShortcutVariant, size: DpSize) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) { ShortcutWidgetContent(config, variant, ThemeMode.Dark, oledBlack = false) }.remoteViews
        }
        lateinit var host: AppWidgetHostView
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            host = AppWidgetHostView(activity)
            host.addView(remoteViews.apply(activity, host))
            // A wallpaper-like ground under the host, so a translucent look is seen against something.
            host.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(AndroidColor.rgb(38, 54, 82), AndroidColor.rgb(86, 62, 96)),
            )
            activity.setContentView(host, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        }
        compose.waitForIdle()
        host.captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** A swatch standing in for a picture: the bytes of a PNG, decodable for a thumbnail. */
    private fun swatch(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun screenshot(): PendingAttachment = PendingAttachment.of(PromptImage(swatch(120, 240, AndroidColor.rgb(52, 120, 246)), "image/png"), id = "img-1")

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()

        /** One cell of a phone's 4-column grid, as the launcher hands it to a 1x1 widget. */
        val CELL = DpSize(72.dp, 72.dp)

        /** A wallpaper-like ground under the quick composer's scrim. */
        val Wallpaper = Brush.linearGradient(listOf(Color(0xFF263652), Color(0xFF563E60)))
    }
}
