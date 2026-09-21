package com.cursorforandroid.screenshots

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.RowDensity
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.widget.ChatsWidgetContent
import com.cursorforandroid.widget.ChatsWidgetOptions
import com.cursorforandroid.widget.WidgetConfigureScreen
import com.cursorforandroid.widget.WidgetData
import com.cursorforandroid.widget.WidgetSizes
import com.cursorforandroid.widget.WidgetSnapshot
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
        capture("127_widget_small", dark, defaults.copy(mode = WidgetMode.Running), WidgetSizes.sizeFor(WidgetLayout.Small))
        capture(
            "128_widget_large_oled_glass",
            dark,
            defaults.copy(appearance = WidgetAppearance(WidgetTheme.Oled, opacity = 70), density = RowDensity.Comfortable, cornerAction = CornerAction.Refresh, cornerStyle = CornerStyle.Glass),
            WidgetSizes.sizeFor(WidgetLayout.Large),
        )
        // One Project's chats, tinted corner button, compact rows without the metadata.
        val project = snapshot.projects.first()
        capture(
            "129_widget_project_tinted",
            dark,
            defaults.copy(mode = WidgetMode.Project, projectId = project.id, density = RowDensity.Compact, cornerStyle = CornerStyle.Tinted, elements = setOf(com.cursorforandroid.domain.RowElement.Status, com.cursorforandroid.domain.RowElement.UnreadDot)),
            DpSize(320.dp, 158.dp),
        )
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
        host.captureRoboImage(File(outDir, "130_widget_preview_layout.png").path, RoborazziOptions())
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
        lateinit var host: AppWidgetHostView
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            // The list's remote adapter is only applied under a real widget host, as on a launcher.
            host = AppWidgetHostView(activity)
            host.addView(remoteViews.apply(activity, host))
            activity.setContentView(host, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        }
        compose.waitForIdle()
        // A launcher's list shows its scrollbar only while scrolling; Robolectric would paint it at rest.
        host.findListViews().forEach { it.isVerticalScrollBarEnabled = false }
        host.captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun android.view.View.findListViews(): List<android.widget.AbsListView> = when (this) {
        is android.widget.AbsListView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).findListViews() }
        else -> emptyList()
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
