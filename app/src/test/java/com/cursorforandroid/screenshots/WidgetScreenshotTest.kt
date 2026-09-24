package com.cursorforandroid.screenshots

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.HeaderElement
import com.cursorforandroid.domain.RowDensity
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.widget.ChatsWidget
import com.cursorforandroid.widget.ChatsWidgetContent
import com.cursorforandroid.widget.ChatsWidgetOptions
import com.cursorforandroid.widget.PreviewHostView
import com.cursorforandroid.widget.ProjectsWidgetFixture
import com.cursorforandroid.widget.WidgetConfigureScreen
import com.cursorforandroid.widget.WidgetData
import com.cursorforandroid.widget.HomeScreenHost
import com.cursorforandroid.widget.WidgetSizes
import com.cursorforandroid.widget.WidgetSnapshot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.flowOf
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
 * Renders the home-screen widget the way a launcher does — the Glance composition to RemoteViews, the RemoteViews
 * to views — over the demo backend, and writes PNGs to `screenshots/` next to the app walkthrough. Same pinned
 * clock, zone and locale as [AppScreenshotTest], so the ages and the demo's rows are the same on every machine.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WidgetScreenshotTest {

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

    @Test
    fun widgets() {
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        // The demo pins its three showcase agents, so every list has rows.
        val snapshot = runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
            WidgetData.snapshot(graph)
        }
        val dark = snapshot.copy(theme = ThemeMode.Dark)
        val defaults = ChatsWidgetSettings()
        // A 4x2 widget on a typical phone grid, then a 4x3 one for the list that has the most to show.
        capture("14_widget_pinned", dark, defaults.copy(mode = WidgetMode.Pinned), DpSize(320.dp, 158.dp))
        capture("15_widget_running", dark, defaults.copy(mode = WidgetMode.Running), DpSize(320.dp, 158.dp))
        capture("16_widget_recent", dark, defaults, DpSize(320.dp, 268.dp))
        capture("17_widget_recent_light", snapshot.copy(theme = ThemeMode.Light), defaults, DpSize(320.dp, 268.dp))
        // The sample rows the widget picker shows before any account is signed in; also the source of
        // res/drawable-nodpi/widget_chats_preview.png, the picker image for launchers before Android 12.
        capture("18_widget_preview", WidgetData.sample(ThemeMode.Dark, FIXED_NOW), defaults.copy(appearance = WidgetAppearance(theme = WidgetTheme.System)), DpSize(320.dp, 158.dp))
        // The other two arrangements: the one-line 2x1 cell, and the two-line rows of a tall placement — the latter
        // in the OLED theme at 70 % over the wallpaper, comfortable rows, a glass corner button that refreshes.
        capture("310_widget_small", dark, defaults.copy(mode = WidgetMode.Running), WidgetSizes.sizeFor(WidgetLayout.Small))
        capture(
            "311_widget_large_oled_glass",
            dark,
            defaults.copy(appearance = WidgetAppearance(WidgetTheme.Oled, opacity = 70), density = RowDensity.Comfortable, cornerAction = CornerAction.Refresh, cornerStyle = CornerStyle.Glass),
            WidgetSizes.sizeFor(WidgetLayout.Large),
        )
        // One Project's chats, tinted corner button, compact rows without the metadata.
        val project = snapshot.projects.first()
        capture(
            "312_widget_project_tinted",
            dark,
            defaults.copy(mode = WidgetMode.Project, projectId = project.id, density = RowDensity.Compact, cornerStyle = CornerStyle.Tinted, elements = setOf(com.cursorforandroid.domain.RowElement.Status, com.cursorforandroid.domain.RowElement.UnreadDot)),
            DpSize(320.dp, 158.dp),
        )
    }

    /**
     * The Projects list ([ProjectsWidgetFixture]: a coordinator at work, a Project whose chats are, one unread under a
     * long name, one failed, one in the default tone, a registry stand-in) in every arrangement, and what it says empty.
     */
    @Test
    fun projectsWidgets() {
        val dark = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, FIXED_NOW)
        val projects = ChatsWidgetSettings(mode = WidgetMode.Projects)
        capture("314_widget_projects", dark, projects, DpSize(320.dp, 158.dp))
        capture("315_widget_projects_light", ProjectsWidgetFixture.snapshot(ThemeMode.Light, FIXED_NOW), projects, DpSize(320.dp, 158.dp))
        capture("316_widget_projects_large", dark, projects, WidgetSizes.sizeFor(WidgetLayout.Large))
        // One row high: a strip of Project icons as wide as the cell allows, "+N" for the rest; a 2x1 says it in words.
        capture("317_widget_projects_small", dark, projects, WidgetSizes.sizeFor(WidgetLayout.Small))
        capture("318_widget_projects_small_narrow", dark, projects, DpSize(250.dp, 60.dp))
        capture("319_widget_projects_small_2x1", dark, projects, DpSize(110.dp, 60.dp))
        // A 3x2 cell, where the long name gives way to the metadata and the count.
        capture("320_widget_projects_narrow", dark, projects, DpSize(250.dp, 158.dp))
        capture("321_widget_projects_extended_off", ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, FIXED_NOW, extendedMode = false), projects, DpSize(320.dp, 158.dp))
        capture("322_widget_projects_empty", ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, FIXED_NOW, extendedMode = true), projects, DpSize(320.dp, 158.dp))
        capture("323_widget_projects_small_extended_off", ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, FIXED_NOW, extendedMode = false), projects, WidgetSizes.sizeFor(WidgetLayout.Small))
    }

    /** The settings screen set to the Projects list, then its sheet of choices: every Project, or one Project's chats. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configureProjects() {
        showOptions(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, FIXED_NOW), ChatsWidgetSettings(mode = WidgetMode.Projects))
        captureScreenRoboImage(File(outDir, "324_widget_configure_projects.png").path, RoborazziOptions())
        compose.onNodeWithTag("option-project").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "325_widget_configure_projects_sheet.png").path, RoborazziOptions())
    }

    /** Without Extended mode, the Projects choice says where the Projects come from. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configureProjectsExtendedOff() {
        showOptions(ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, FIXED_NOW, extendedMode = false), ChatsWidgetSettings(mode = WidgetMode.Projects))
        captureScreenRoboImage(File(outDir, "326_widget_configure_projects_extended_off.png").path, RoborazziOptions())
    }

    /** The header's parts one at a time off the defaults (the list's name, refresh and "+" on, the cube off), and none of them. */
    @Test
    fun headers() {
        val dark = WidgetData.sample(ThemeMode.Dark, FIXED_NOW)
        val defaults = ChatsWidgetSettings()
        capture("408_widget_header_logo", dark, defaults.toggled(HeaderElement.Logo), FOUR_BY_TWO)
        capture("409_widget_header_no_title", dark, defaults.toggled(HeaderElement.Title), FOUR_BY_TWO)
        capture("410_widget_header_none", dark, defaults.copy(header = emptySet()), FOUR_BY_TWO)
        capture("413_widget_header_light", WidgetData.sample(ThemeMode.Light, FIXED_NOW), defaults, FOUR_BY_TWO)
    }

    /**
     * A placement too short for the header — a 4x2 with the phone turned — where it gives its room to the rows unless
     * told to stay. On a landscape screen, so the placement's width fits it.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-night-420dpi")
    fun headersLandscape() {
        val dark = WidgetData.sample(ThemeMode.Dark, FIXED_NOW)
        capture("411_widget_header_auto_hidden", dark, ChatsWidgetSettings(), FOUR_BY_TWO_LANDSCAPE)
        capture("412_widget_header_kept", dark, ChatsWidgetSettings(headerAutoHide = false), FOUR_BY_TWO_LANDSCAPE)
    }

    /**
     * A 4x4 Projects widget as the home screen draws it — [ChatsWidget] composed for the sizes the launcher reports,
     * shown by a widget host at the placement's space — next to the settings screen's preview of the same placement.
     * The two are one picture: one-line rows, the age and the counts at each row's end.
     */
    @Test
    fun projectsLiveAndPreview() {
        val dark = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, FIXED_NOW)
        val projects = ChatsWidgetSettings(mode = WidgetMode.Projects)
        captureLive("414_widget_projects_live_4x4", dark, projects, FOUR_BY_FOUR, FOUR_BY_FOUR_LANDSCAPE)
        capturePreview("415_widget_projects_preview_4x4", dark, projects, FOUR_BY_FOUR)
        captureLive("416_widget_projects_live_4x2", dark, projects, FOUR_BY_TWO, FOUR_BY_TWO_LANDSCAPE)
        capturePreview("417_widget_projects_preview_4x2", dark, projects, FOUR_BY_TWO)
    }

    /** The settings screen for a placed 4x4 Projects widget: the preview is that placement, rows and all. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configurePlaced() {
        showOptions(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, FIXED_NOW), ChatsWidgetSettings(mode = WidgetMode.Projects), placement = FOUR_BY_FOUR)
        captureScreenRoboImage(File(outDir, "418_widget_configure_4x4.png").path, RoborazziOptions())
    }

    /** A placement wider than the stage (a 4x4 in landscape) is shown whole, scaled down to fit. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configurePlacedWide() {
        showOptions(WidgetData.sample(ThemeMode.Dark, FIXED_NOW), ChatsWidgetSettings(), placement = FOUR_BY_FOUR_LANDSCAPE)
        captureScreenRoboImage(File(outDir, "419_widget_configure_wide.png").path, RoborazziOptions())
    }

    /** The header's options, with the logo on: each part the header can show, and whether it hides itself on short placements. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configureHeader() {
        showOptions(WidgetData.sample(ThemeMode.Dark, FIXED_NOW), ChatsWidgetSettings().toggled(HeaderElement.Logo), placement = FOUR_BY_TWO)
        compose.onNodeWithTag("option-header").performScrollTo()
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "420_widget_configure_header.png").path, RoborazziOptions())
    }

    /** The same screen under the light app theme. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun configureLight() {
        showOptions(WidgetData.sample(ThemeMode.Light, FIXED_NOW), ChatsWidgetSettings(), placement = FOUR_BY_TWO, appMode = ThemeMode.Light)
        captureScreenRoboImage(File(outDir, "421_widget_configure_light.png").path, RoborazziOptions())
    }

    private fun showOptions(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, placement: DpSize? = null, appMode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            CursorTheme(mode = appMode) {
                WidgetConfigureScreen(title = "Chats widget", subtitle = "What it shows and how it looks", onDone = {}, onClose = {}) {
                    ChatsWidgetOptions(settings = settings, snapshot = snapshot, onChange = {}, placement = placement)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /**
     * The picker's static picture (`previewLayout`): what Android 12–14 launchers show, and what Android 15 falls back to
     * before the app has published its generated preview or after a reboot took it. Inflated as a launcher inflates it —
     * under a widget host, through RemoteViews' class filter — so a layout the launcher would refuse fails here too.
     */
    @Test
    fun previewLayout() {
        val size = DpSize(320.dp, 158.dp)
        lateinit var host: AppWidgetHostView
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            host = AppWidgetHostView(activity)
            val inflater = android.view.LayoutInflater.from(activity).cloneInContext(activity)
            inflater.filter = android.view.LayoutInflater.Filter { it.isAnnotationPresent(android.widget.RemoteViews.RemoteView::class.java) }
            host.addView(inflater.inflate(com.cursorforandroid.R.layout.widget_chats_preview, host, false))
            activity.setContentView(host, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        }
        compose.waitForIdle()
        host.captureRoboImage(File(outDir, "313_widget_preview_layout.png").path, RoborazziOptions())
    }

    /** The screen the launcher opens when the widget is placed (and the widget's title reopens): preview on its stage, then the options. */
    @Test
    fun configure() {
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        val snapshot = runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
            WidgetData.snapshot(graph)
        }.copy(theme = ThemeMode.Dark)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                WidgetConfigureScreen(title = "Chats widget", subtitle = "What it shows and how it looks", onDone = {}, onClose = {}) {
                    ChatsWidgetOptions(settings = ChatsWidgetSettings(), snapshot = snapshot, onChange = {})
                }
            }
        }
        compose.waitForIdle()
        // The preview composes its RemoteViews a moment after the first frame.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "19_widget_configure.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun capture(name: String, snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, size: DpSize) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) { ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID) }.remoteViews
        }
        // The list's remote adapter is only applied under a real widget host, as on a launcher.
        captureHost(name, size) { activity -> AppWidgetHostView(activity).also { it.addView(remoteViews.apply(activity, it)) } }
    }

    /** The home-screen widget: [ChatsWidget] composed for the portrait and landscape sizes, the host picking one at [portrait]. */
    private fun captureLive(name: String, snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, portrait: DpSize, landscape: DpSize) {
        val options = HomeScreenHost.launcherOptions(portrait, landscape)
        val views = runBlocking {
            ChatsWidget { flowOf(snapshot) }.compose(context, options = options, state = preferencesOf(ChatsWidget.SETTINGS_KEY to settings.encode()))
        }
        captureHost(name, portrait) { activity -> HomeScreenHost.host(activity, views, portrait) }
    }

    /** The settings screen's preview of a placement ([WidgetPreview]'s host, unscaled). */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun capturePreview(name: String, snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, size: DpSize) {
        val views = runBlocking {
            GlanceRemoteViews().compose(context, size) { ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID) }.remoteViews
        }
        captureHost(name, size) { activity -> PreviewHostView(activity).also { it.show(views) } }
    }

    private fun captureHost(name: String, size: DpSize, host: (ComponentActivity) -> AppWidgetHostView) {
        lateinit var view: AppWidgetHostView
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            view = host(activity)
            activity.setContentView(view, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        }
        compose.waitForIdle()
        // A launcher's list shows its scrollbar only while scrolling; Robolectric would paint it at rest.
        view.findListViews().forEach { it.isVerticalScrollBarEnabled = false }
        view.captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun android.view.View.findListViews(): List<android.widget.AbsListView> = when (this) {
        is android.widget.AbsListView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).findListViews() }
        else -> emptyList()
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()

        /** A 4x2 and a 4x4 placement on this 411dp phone, in portrait and in landscape, as a launcher reports them. */
        val FOUR_BY_TWO = DpSize(338.dp, 176.dp)
        val FOUR_BY_TWO_LANDSCAPE = DpSize(610.dp, 110.dp)
        val FOUR_BY_FOUR = DpSize(338.dp, 380.dp)
        val FOUR_BY_FOUR_LANDSCAPE = DpSize(610.dp, 240.dp)
    }
}
