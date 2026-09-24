package com.cursorforandroid.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.HeaderElement
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings screen's preview is the home-screen widget. The widget is composed as the launcher has it composed
 * — the [ChatsWidget] itself, its settings in its Glance state, once per size the placement reports — and shown by
 * a widget host laid out at the placement's space, which picks the composition it draws; the preview is composed
 * as the settings screen composes it, at the size [WidgetPlacement] reads for that placement. Every text and image
 * of the two, and where each sits, has to be the same, rows included.
 *
 * This is what drifted before: the widget picked its arrangement from a set of bucket sizes by the launcher's
 * nearest-fit rule while the preview pinned one of its own, and a Project's row had a second, two-line design for
 * tall widgets that the preview never showed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WidgetPreviewParityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_788_900_000_000L
    private val projects = ChatsWidgetSettings(mode = WidgetMode.Projects)

    /** A 4x1, 4x2 and 4x4 placement on a 411dp phone: portrait, then landscape, as a launcher reports them. */
    private val fourByOne = HomeScreenHost.launcherOptions(portrait = DpSize(338.dp, 68.dp), landscape = DpSize(610.dp, 48.dp))
    private val fourByTwo = HomeScreenHost.launcherOptions(portrait = DpSize(338.dp, 176.dp), landscape = DpSize(610.dp, 110.dp))
    private val fourByFour = HomeScreenHost.launcherOptions(portrait = DpSize(338.dp, 380.dp), landscape = DpSize(610.dp, 240.dp))

    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun releaseClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `a 4x4 Projects widget is its preview, one-line rows with the counts at their end`() {
        val snapshot = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now)
        listOf(false, true).forEach { landscape -> assertSame(snapshot, projects, fourByFour, landscape) }

        val rows = live(snapshot, projects, fourByFour, landscape = false).rows()
        assertThat(rows).hasSize(ProjectsWidgetFixture.listedIds.size)
        rows.forEach { row ->
            val texts = row.leaves().filter { it.text != null }
            val centres = texts.map { it.centreY }
            assertWithMessage("one line: ${texts.map { it.text }}").that(centres.max() - centres.min()).isAtMost(1)
        }
        val first = rows.first().leaves().filter { it.text != null }
        assertThat(first.map { it.text }).containsExactly("Cursor for Android", "2m", "4").inOrder()
        // The age and the count sit at the row's end, after the name's slot, not under it.
        assertThat(first[1].left).isGreaterThan(first[0].left + first[0].width)
    }

    @Test
    fun `a 4x2 widget is its preview, and turned to landscape its header gives the rows its room on both`() {
        val snapshot = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now)
        assertSame(snapshot, projects, fourByTwo, landscape = false)
        assertSame(snapshot, projects, fourByTwo, landscape = true)
        assertThat(live(snapshot, projects, fourByTwo, landscape = false).frameTexts()).contains("Projects")
        assertThat(live(snapshot, projects, fourByTwo, landscape = true).frameTexts()).doesNotContain("Projects")
        // Not on one line, which is what the nearest bucket size drew a 4x2 placement as.
        assertThat(live(snapshot, projects, fourByTwo, landscape = false).rows()).isNotEmpty()
    }

    @Test
    fun `the chats lists are their previews too, two-line rows and all`() {
        val snapshot = WidgetData.sample(ThemeMode.Dark, now)
        val recent = ChatsWidgetSettings(mode = WidgetMode.Recent)
        listOf(fourByTwo, fourByFour).forEach { options ->
            listOf(false, true).forEach { landscape -> assertSame(snapshot, recent, options, landscape) }
        }
    }

    @Test
    fun `the Projects strip holds as many Projects on the home screen as in the preview`() {
        val snapshot = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, now)
        listOf(false, true).forEach { landscape -> assertSame(snapshot, projects, fourByOne, landscape) }
        listOf(false, true).forEach { landscape -> assertSame(snapshot, projects.toggled(HeaderElement.Logo), fourByOne, landscape) }
    }

    @Test
    fun `every header arrangement is its preview`() {
        val snapshot = WidgetData.sample(ThemeMode.Dark, now)
        val variants = listOf(
            ChatsWidgetSettings(),
            ChatsWidgetSettings().toggled(HeaderElement.Logo),
            ChatsWidgetSettings().toggled(HeaderElement.Title),
            ChatsWidgetSettings(header = emptySet()),
            ChatsWidgetSettings(headerAutoHide = false),
        )
        variants.forEach { settings ->
            listOf(false, true).forEach { landscape -> assertSame(snapshot, settings, fourByTwo, landscape) }
        }
    }

    private fun assertSame(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, options: Bundle, landscape: Boolean) {
        val live = live(snapshot, settings, options, landscape)
        val preview = preview(snapshot, settings, options, landscape)
        val what = "${settings.mode} ${settings.header} autoHide=${settings.headerAutoHide} at ${WidgetPlacement.current(options, landscape)}"
        assertWithMessage("frame of $what").that(live.frameLeaves()).isNotEmpty()
        assertWithMessage("frame of $what").that(preview.frameLeaves()).isEqualTo(live.frameLeaves())
        assertWithMessage("rows of $what").that(preview.rows().map { it.leaves() }).isEqualTo(live.rows().map { it.leaves() })
    }

    /** The widget as the launcher gets it: [ChatsWidget] composed for each size the placement reports, in a host at the orientation's space. */
    private fun live(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, options: Bundle, landscape: Boolean): AppWidgetHostView {
        val widget = ChatsWidget { flowOf(snapshot) }
        val views = runBlocking { widget.compose(app, options = options, state = preferencesOf(ChatsWidget.SETTINGS_KEY to settings.encode())) }
        val space = HomeScreenHost.space(options, landscape)
        return show(space) { activity -> HomeScreenHost.host(activity, views, space) }
    }

    /** The widget as the settings screen previews it (see [WidgetPreview]): composed at the placement's size, then applied. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun preview(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, options: Bundle, landscape: Boolean): AppWidgetHostView {
        val size = WidgetPlacement.current(options, landscape)!!
        val views = runBlocking { GlanceRemoteViews().compose(app, size) { ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID) }.remoteViews }
        return show(size) { activity -> PreviewHostView(activity).also { it.show(views) } }
    }

    private fun show(space: DpSize, host: (ComponentActivity) -> AppWidgetHostView): AppWidgetHostView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val view = host(activity)
        activity.setContentView(view, ViewGroup.LayoutParams((space.width.value * density).toInt(), (space.height.value * density).toInt()))
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    /** One drawn text or image: what it says, and where it sits in what it was measured against, in pixels. */
    private data class Leaf(val text: String?, val image: String?, val left: Int, val top: Int, val width: Int, val height: Int) {
        val centreY: Int get() = top + height / 2
    }

    /** Everything drawn outside the list: the header, a notice, the one-line arrangement, the corner button. */
    private fun AppWidgetHostView.frameLeaves(): List<Leaf> = leavesIn(this, skipLists = true)

    private fun AppWidgetHostView.frameTexts(): List<String> = frameLeaves().mapNotNull { it.text }

    /** The list's rows, built by its adapter as a launcher's list builds them and laid out at the list's width. */
    private fun AppWidgetHostView.rows(): List<View> {
        val list = descendants().filterIsInstance<AbsListView>().singleOrNull() ?: return emptyList()
        val adapter = list.adapter ?: return emptyList()
        return (0 until adapter.count).map { index ->
            adapter.getView(index, null, list).also { row ->
                row.measure(MeasureSpec.makeMeasureSpec(list.width - list.paddingLeft - list.paddingRight, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            }
        }.filter { row -> row.descendants().any { it is TextView && it.text.isNotEmpty() } }
    }

    private fun View.leaves(): List<Leaf> = leavesIn(this, skipLists = false)

    /**
     * The texts and images [root] draws. A tap target's ripple is left out: it paints nothing at rest, and only the
     * home-screen widget has tap targets (the preview is a picture).
     */
    private fun leavesIn(root: View, skipLists: Boolean): List<Leaf> = root.descendants(skipLists)
        .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 && ancestorsVisible(it, root) }
        .mapNotNull { view ->
            val (left, top) = offset(view, root)
            when {
                view is TextView -> view.text.toString().takeIf { it.isNotEmpty() }?.let { Leaf(it, null, left, top, view.width, view.height) }
                view is ImageView && view.isRipple() -> null
                view is ImageView -> Leaf(null, view.contentDescription?.toString() ?: "", left, top, view.width, view.height)
                else -> null
            }
        }

    /** Glance's ripple overlay: a ripple, which Glance wraps in a layer list of its own. */
    private fun ImageView.isRipple(): Boolean = drawable?.isRipple() == true

    private fun Drawable.isRipple(): Boolean =
        this is RippleDrawable || (this is LayerDrawable && numberOfLayers > 0 && (0 until numberOfLayers).all { getDrawable(it)?.isRipple() == true })

    private fun ancestorsVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null && current !== root) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun offset(view: View, root: View): Pair<Int, Int> {
        var left = 0
        var top = 0
        var current: View? = view
        while (current != null && current !== root) {
            left += current.left - current.scrollX
            top += current.top - current.scrollY
            current = current.parent as? View
        }
        return left to top
    }

    private fun View.descendants(skipLists: Boolean = false): List<View> = listOf(this) + when {
        this is AbsListView && skipLists -> emptyList()
        this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).descendants(skipLists) }
        else -> emptyList()
    }
}
