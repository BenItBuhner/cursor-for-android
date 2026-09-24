package com.cursorforandroid.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.RowElement
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.theme.ProjectPalette
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Projects widget as a launcher gets it — the Glance composition to RemoteViews, the RemoteViews to views under
 * a widget host: a row per Project in its icon and colour, working and badged as the sidebar draws it, the Projects
 * on one line in the 1-row cells, and what an empty list says.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectsWidgetTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_788_900_000_000L
    private val projects = ChatsWidgetSettings(mode = WidgetMode.Projects)
    private val medium = DpSize(320.dp, 158.dp)

    @Test
    fun `a row per Project, in the sidebar's order, each with its icon, its name and its count of chats`() {
        val host = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now), projects, medium)
        val rows = host.rowViews()
        assertThat(rows).hasSize(ProjectsWidgetFixture.listedIds.size)
        // The icon is described by the Project's name; the row reads its name and age, then the count.
        assertThat(rows.map { it.iconNames() }).containsExactly(
            emptyList<String>(), // The coordinator at work: its icon is the working dots.
            listOf(ProjectsWidgetFixture.LONG_NAME),
            listOf("Visual engine"),
            listOf("Market replay"),
            listOf("Codex Poly Bot"),
            listOf("Notes"),
            listOf("Home automation"),
        ).inOrder()
        assertThat(rows.first().texts()).containsExactly("Cursor for Android", "2m", "4").inOrder()
        assertThat(rows[2].texts()).containsExactly("Visual engine", "15m", "3").inOrder()
        // No chats, no count; a stand-in has no age until its row is fetched.
        assertThat(rows[4].texts()).containsExactly("Codex Poly Bot", "3h").inOrder()
        assertThat(rows.last().texts()).containsExactly("Home automation")
        assertThat(host.texts()).contains("Projects")
    }

    @Test
    fun `a working coordinator is the working dots in its colour, and working chats put the dots by the count`() {
        val rows = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now), projects, medium).rowViews()
        val android = rows.first().progressBars()
        assertThat(android).hasSize(2)
        assertThat(android.first().indeterminateTintList?.defaultColor).isEqualTo(ProjectPalette.color("purple", dark = true)!!.toArgb())
        // Visual engine's coordinator is idle; two of its chats work.
        assertThat(rows[2].progressBars()).hasSize(1)
        assertThat(rows[4].progressBars()).isEmpty()
        // With Status off, nothing moves.
        val still = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now), projects.copy(elements = projects.elements - RowElement.Status), medium).rowViews()
        assertThat(still.flatMap { it.progressBars() }).isEmpty()
        assertThat(still.first().iconNames()).containsExactly("Cursor for Android")
    }

    @Test
    fun `an unread Project and a failed one are badged on the icon`() {
        val rows = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now), projects, medium).rowViews()
        assertThat(rows[1].descriptions()).contains("Unread")
        assertThat(rows[3].descriptions()).contains("Failed")
        assertThat(rows[4].descriptions()).containsNoneOf("Unread", "Failed")
    }

    @Test
    fun `an empty Projects list says why`() {
        val off = render(ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, now, extendedMode = false), projects, medium)
        assertThat(off.texts()).contains("Turn on Extended mode to list your Projects")
        assertThat(off.rowViews()).isEmpty()

        val none = render(ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, now, extendedMode = true), projects, medium)
        assertThat(none.texts()).contains("No Projects yet")

        val offOneLine = render(ProjectsWidgetFixture.withoutProjects(ThemeMode.Dark, now, extendedMode = false), projects, DpSize(320.dp, 60.dp))
        assertThat(offOneLine.texts()).containsAtLeast("Projects", "Needs Extended mode")

        // Projects the list already holds are listed with Extended mode off all the same.
        val held = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now, extendedMode = false), projects, medium)
        assertThat(held.rowViews()).hasSize(ProjectsWidgetFixture.listedIds.size)
    }

    @Test
    fun `one row high, the Projects are a strip of icons as wide as the cell allows`() {
        val snapshot = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now)
        val wide = render(snapshot, projects, DpSize(320.dp, 60.dp))
        assertThat(wide.rowViews()).isEmpty()
        assertThat(wide.iconNames()).containsExactly(ProjectsWidgetFixture.LONG_NAME, "Visual engine", "Market replay", "Codex Poly Bot", "Notes", "Home automation").inOrder()
        assertThat(wide.progressBars()).hasSize(1)
        assertThat(wide.texts().filter { it.startsWith("+") }).isEmpty()
        // Seven Projects and the cube, the gaps and the corner button are more than a Glance Row holds; none is dropped.
        assertThat(wide.descriptions()).contains("New chat")

        // Too narrow for them all: the first few, the working coordinator's dots among them, then "+N" for the rest.
        val narrowWidth = 250.dp
        val slots = projectSlots(narrowWidth, CornerButtonSpec.of(app, projects, WidgetLayout.Small))
        assertThat(slots).isIn(MIN_STRIP_SLOTS until ProjectsWidgetFixture.listedIds.size)
        val narrow = render(snapshot, projects, DpSize(narrowWidth, 60.dp))
        assertThat(narrow.progressBars()).hasSize(1)
        assertThat(narrow.iconNames()).containsExactlyElementsIn(listOf(ProjectsWidgetFixture.LONG_NAME, "Visual engine", "Market replay", "Codex Poly Bot").take(slots - 2)).inOrder()
        assertThat(narrow.texts()).contains("+${ProjectsWidgetFixture.listedIds.size - (slots - 1)}")

        // A 2x1 has room for one: it says what the list holds instead.
        val tiny = render(snapshot, projects, DpSize(110.dp, 60.dp))
        assertThat(tiny.iconNames()).isEmpty()
        assertThat(tiny.texts()).containsAtLeast("Projects", "2 running · 7 Projects")
        // The words give way to the corner button, not the button to the words.
        assertThat(tiny.rightEdgeOf("New chat")).isAtMost(tiny.width)
        assertThat(wide.rightEdgeOf("New chat")).isAtMost(wide.width)
    }

    @Test
    fun `tapping a Project on the strip opens its coordinator`() {
        val host = render(ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now), projects, DpSize(320.dp, 60.dp))
        val icon = host.descendants().filterIsInstance<ImageView>().first { it.contentDescription == "Visual engine" }
        generateSequence(icon as View) { it.parent as? View }.first { it.hasOnClickListeners() }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.data?.toString()).endsWith("/agents/p-visual")
    }

    @Test
    fun `a Project's icon is drawn once per size, white on clear, and a name the catalog lacks is the cube`() {
        val rocket = ProjectIconBitmaps.bitmap(app, "rocket", 16f)
        val density = app.resources.displayMetrics.density
        assertThat(rocket.width).isEqualTo(Math.round(16 * density))
        assertThat(ProjectIconBitmaps.bitmap(app, "rocket", 16f)).isSameInstanceAs(rocket)
        val pixels = IntArray(rocket.width * rocket.height).also { rocket.getPixels(it, 0, rocket.width, 0, 0, rocket.width, rocket.height) }
        assertThat(pixels.any { Color.alpha(it) == 255 && Color.red(it) == 255 && Color.green(it) == 255 && Color.blue(it) == 255 }).isTrue()
        assertThat(pixels.count { Color.alpha(it) == 0 }).isGreaterThan(pixels.size / 3)
        assertThat(ProjectIconBitmaps.bitmap(app, "no-such-icon", 16f)).isSameInstanceAs(ProjectIconBitmaps.bitmap(app, null, 16f))
        assertThat(ProjectIconBitmaps.bitmap(app, "rocket", 100f).width).isEqualTo(ProjectIconBitmaps.MAX_PX)
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, size: DpSize): AppWidgetHostView {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(app, size) { ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID, false, now) }.remoteViews
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val host = AppWidgetHostView(activity)
        host.addView(remoteViews.apply(activity, host))
        activity.setContentView(host, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        shadowOf(Looper.getMainLooper()).idle()
        return host
    }

    /** The list's Project rows, built by the adapter the launcher would use; not the spacer clear of the corner button. */
    private fun AppWidgetHostView.rowViews(): List<View> {
        val list = descendants().filterIsInstance<AbsListView>().singleOrNull() ?: return emptyList()
        val adapter = list.adapter ?: return emptyList()
        return (0 until adapter.count).map { adapter.getView(it, null, list) }.filter { it.texts().isNotEmpty() }
    }

    /** The Project icons: the images described by a name rather than a state. */
    private fun View.iconNames(): List<String> = descendants().filterIsInstance<ImageView>()
        .mapNotNull { it.contentDescription?.toString() }
        .filter { it !in setOf("Unread", "Failed", "Cursor") && !it.startsWith("Choose") && it != "Refresh" && it != "New chat" }

    private fun View.progressBars(): List<ProgressBar> = descendants().filterIsInstance<ProgressBar>()

    private fun View.texts(): List<String> = descendants().filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }

    private fun View.descriptions(): List<String> = descendants().mapNotNull { it.contentDescription?.toString() }

    /** Where the view described as [description] ends, in this host's own pixels. */
    private fun AppWidgetHostView.rightEdgeOf(description: String): Int {
        val view = descendants().first { it.contentDescription == description }
        val at = IntArray(2).also(view::getLocationInWindow)
        val origin = IntArray(2).also(::getLocationInWindow)
        return at[0] - origin[0] + view.width
    }

    private fun View.descendants(): List<View> = listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()
}
