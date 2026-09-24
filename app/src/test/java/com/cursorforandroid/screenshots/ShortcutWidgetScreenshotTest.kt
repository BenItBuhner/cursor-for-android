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
import com.cursorforandroid.domain.ShortcutWidgetSettings
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.widget.ShortcutTargets
import com.cursorforandroid.widget.WidgetConfigureScreen
import com.cursorforandroid.widget.ShortcutVariant
import com.cursorforandroid.widget.ShortcutWidgetOptions
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
        captureWidget("327_shortcut_widget_solid", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.Button, CELL)
        captureWidget("328_shortcut_widget_tinted", ShortcutWidgetSettings(ShortcutStyle.Tinted), ShortcutVariant.Button, CELL)
        captureWidget("329_shortcut_widget_glass", ShortcutWidgetSettings(ShortcutStyle.Glass), ShortcutVariant.Button, CELL)
        captureWidget("330_shortcut_widget_icon_only", ShortcutWidgetSettings(ShortcutStyle.IconOnly), ShortcutVariant.Button, CELL)
        // Pointed at a Project, the button wears the cube.
        captureWidget("331_shortcut_widget_project", ShortcutWidgetSettings(ShortcutStyle.Solid).withTarget(ShortcutTarget.Project("bc-1", "Cursor for Android")), ShortcutVariant.Button, CELL)
    }

    /** The compose bar across four cells, and the composer box it becomes two rows tall. */
    @Test
    fun bars() {
        captureWidget("332_compose_bar_widget", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.Bar, DpSize(320.dp, 72.dp))
        captureWidget("333_compose_bar_widget_tinted", ShortcutWidgetSettings(ShortcutStyle.Tinted), ShortcutVariant.Bar, DpSize(320.dp, 72.dp))
        captureWidget("334_compose_bar_widget_tall", ShortcutWidgetSettings(ShortcutStyle.Tinted), ShortcutVariant.TallBar, DpSize(320.dp, 140.dp))
    }

    /**
     * The same widgets under the light app theme, on a light wallpaper; black for an OLED-black app; and the widget's own
     * theme pinned against the app's.
     */
    @Test
    fun themes() {
        captureWidget("339_shortcut_widget_solid_light", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.Button, CELL, ThemeMode.Light, ground = LightGround)
        captureWidget("340_shortcut_widget_glass_light", ShortcutWidgetSettings(ShortcutStyle.Glass), ShortcutVariant.Button, CELL, ThemeMode.Light, ground = LightGround)
        captureWidget("341_shortcut_widget_solid_oled", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.Button, CELL, oledBlack = true, ground = BlackGround)
        captureWidget("342_compose_bar_widget_light", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.Bar, DpSize(320.dp, 72.dp), ThemeMode.Light, ground = LightGround)
        captureWidget("343_compose_bar_widget_tall_light", ShortcutWidgetSettings(ShortcutStyle.Solid), ShortcutVariant.TallBar, DpSize(320.dp, 140.dp), ThemeMode.Light, ground = LightGround)
        val pinnedDark = ShortcutWidgetSettings(ShortcutStyle.Solid, appearance = WidgetAppearance(theme = WidgetTheme.Dark))
        captureWidget("344_compose_bar_widget_pinned_dark", pinnedDark, ShortcutVariant.Bar, DpSize(320.dp, 72.dp), ThemeMode.Light, ground = LightGround)
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
        capture("335_quick_composer")
        scene.value = drafted to true
        capture("336_quick_composer_drafted")
        scene.value = drafted.copy(error = "Cannot start Cloud Agent: the repository is not connected to Cursor's GitHub app.") to true
        capture("337_quick_composer_refused")
    }

    /**
     * The shared widget settings screen ([WidgetConfigureScreen], the Chats widget's chrome) with this kind's body:
     * the live preview, what a tap opens, the button's look, and the appearance section every widget carries.
     */
    @Test
    fun configure() {
        showConfigure(ShortcutWidgetSettings(ShortcutStyle.Solid, appearance = WidgetAppearance(theme = WidgetTheme.App, opacity = 100)), ThemeMode.Dark)
        capture("338_shortcut_widget_configure")
    }

    /** The settings screen under the light app theme: the preview and the swatches are the light widget. */
    @Test
    fun configureLight() {
        showConfigure(ShortcutWidgetSettings(ShortcutStyle.Solid), ThemeMode.Light)
        capture("345_shortcut_widget_configure_light")
    }

    /**
     * The light app with the widget pinned dark: the preview and every swatch are the dark widget, the swatches each on
     * a disc of the dark home screen so they read against the light page.
     */
    @Test
    fun configurePinnedDark() {
        showConfigure(ShortcutWidgetSettings(ShortcutStyle.Solid, appearance = WidgetAppearance(theme = WidgetTheme.Dark)), ThemeMode.Light)
        capture("346_shortcut_widget_configure_pinned_dark")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showConfigure(settings: ShortcutWidgetSettings, appMode: ThemeMode) {
        val sample = WidgetData.sample(appMode, FIXED_NOW)
        val pinned = sample.copy(local = LocalAgentState(pinnedIds = setOf("bc-preview-1", "bc-preview-3")))
        val targets = ShortcutTargets(pinned = AgentListOrganizer.organize(pinned.agents, pinned.prefs, pinned.local, nowMillis = FIXED_NOW).firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows.orEmpty())
        compose.setContent {
            CursorTheme(mode = appMode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    WidgetConfigureScreen(title = "New chat button", subtitle = "What it opens and how it looks", onDone = {}, onClose = {}) {
                        ShortcutWidgetOptions(settings = settings, variant = ShortcutVariant.Button, targets = targets, appMode = appMode, appOledBlack = false, onChange = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        // The stage composes the widget to RemoteViews a moment after the first frame.
        compose.mainClock.advanceTimeBy(500)
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

    /** [ground] is the wallpaper under the host, so a translucent look is seen against something. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun captureWidget(
        name: String,
        settings: ShortcutWidgetSettings,
        variant: ShortcutVariant,
        size: DpSize,
        appMode: ThemeMode = ThemeMode.Dark,
        oledBlack: Boolean = false,
        ground: IntArray = DarkGround,
    ) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) { ShortcutWidgetContent(settings, variant, appMode = appMode, appOledBlack = oledBlack) }.remoteViews
        }
        lateinit var host: AppWidgetHostView
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            host = AppWidgetHostView(activity)
            host.addView(remoteViews.apply(activity, host))
            host.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, ground)
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

        /** Wallpapers under a widget: the dark one [Wallpaper] is, a light one, and an OLED user's black. */
        val DarkGround = intArrayOf(AndroidColor.rgb(38, 54, 82), AndroidColor.rgb(86, 62, 96))
        val LightGround = intArrayOf(AndroidColor.rgb(206, 218, 236), AndroidColor.rgb(232, 214, 226))
        val BlackGround = intArrayOf(AndroidColor.BLACK, AndroidColor.BLACK)
    }
}
