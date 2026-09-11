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
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.widget.ChatsWidgetContent
import com.cursorforandroid.widget.WidgetConfigureScreen
import com.cursorforandroid.widget.WidgetData
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
        // A 4x2 widget on a typical phone grid, then a 4x3 one for the list that has the most to show.
        capture("14_widget_pinned", dark, WidgetMode.Pinned, DpSize(320.dp, 158.dp))
        capture("15_widget_running", dark.copy(prefs = dark.prefs.copy(showRuntime = true)), WidgetMode.Running, DpSize(320.dp, 158.dp))
        capture("16_widget_recent", dark, WidgetMode.Recent, DpSize(320.dp, 268.dp))
        capture("17_widget_recent_light", snapshot.copy(theme = ThemeMode.Light), WidgetMode.Recent, DpSize(320.dp, 268.dp))
        // The sample rows the widget picker shows before any account is signed in; also the source of
        // res/drawable-nodpi/widget_chats_preview.png, the picker image for launchers before Android 12.
        capture("18_widget_preview", WidgetData.sample(ThemeMode.Dark, FIXED_NOW), WidgetMode.Recent, DpSize(320.dp, 158.dp))
    }

    /** The screen the launcher opens when the widget is placed (and the widget's title reopens). */
    @Test
    fun configure() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                WidgetConfigureScreen(selected = WidgetMode.Recent, onPick = {}, onClose = {})
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "19_widget_configure.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun capture(name: String, snapshot: WidgetSnapshot, mode: WidgetMode, size: DpSize) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) { ChatsWidgetContent(snapshot, mode, AppWidgetManager.INVALID_APPWIDGET_ID) }.remoteViews
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
