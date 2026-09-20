package com.cursorforandroid.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.graphics.drawable.AnimationDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.WidgetMode
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
 * The two things that move in the widget are ProgressBars, because a ProgressBar is the one RemoteViews view that
 * starts its own animation: the running row's dot grid is a frame animation played as an indeterminate drawable, and
 * the header's refresh button becomes an indeterminate spinner while its refresh runs. An `Image` of either would
 * hold one frame for ever — which is what the widget did before.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WidgetIndicatorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_788_900_000_000L

    @Test
    fun `a running row's glyph is a frame animation a ProgressBar plays`() {
        val host = render(WidgetData.sample(ThemeMode.Dark, now), WidgetMode.Running, refreshing = false)

        val glyphs = host.rowViews().flatMap { it.progressBars() }
        assertThat(glyphs).isNotEmpty()
        glyphs.forEach { bar ->
            assertThat(bar.isIndeterminate).isTrue()
            val steps = bar.indeterminateDrawable as AnimationDrawable
            assertThat(steps.numberOfFrames).isEqualTo(8)
            assertThat((0 until steps.numberOfFrames).map(steps::getDuration).toSet()).containsExactly(175)
            assertThat(steps.isOneShot).isFalse()
        }
    }

    @Test
    fun `the header holds no spinner at rest and one while its refresh runs`() {
        val atRest = render(WidgetData.sample(ThemeMode.Dark, now), WidgetMode.Recent, refreshing = false)
        assertThat(atRest.headerProgressBars()).isEmpty()

        val refreshing = render(WidgetData.sample(ThemeMode.Dark, now), WidgetMode.Recent, refreshing = true)
        val spinners = refreshing.headerProgressBars()
        assertThat(spinners).hasSize(1)
        assertThat(spinners.single().isIndeterminate).isTrue()
    }

    @Test
    fun `an empty list that is being fetched says so instead of asking for the app`() {
        val snapshot = WidgetData.sample(ThemeMode.Dark, now).copy(agents = emptyList(), hasLoaded = false)

        val waiting = render(snapshot, WidgetMode.Recent, refreshing = true)
        assertThat(waiting.texts()).contains("Loading chats…")

        val idle = render(snapshot, WidgetMode.Recent, refreshing = false)
        assertThat(idle.texts()).contains("Open Cursor to load your chats")
    }

    /** The Glance composition to RemoteViews, the RemoteViews to views under a widget host, as a launcher does it. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(snapshot: WidgetSnapshot, mode: WidgetMode, refreshing: Boolean): AppWidgetHostView {
        val size = DpSize(320.dp, 158.dp)
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(app, size) { ChatsWidgetContent(snapshot, mode, AppWidgetManager.INVALID_APPWIDGET_ID, refreshing, now) }.remoteViews
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val host = AppWidgetHostView(activity)
        // The list's remote adapter is only applied under a real widget host, as on a launcher.
        host.addView(remoteViews.apply(activity, host))
        activity.setContentView(host, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        shadowOf(Looper.getMainLooper()).idle()
        return host
    }

    /** Every row view of the widget's list, built by the adapter the launcher would use. */
    private fun AppWidgetHostView.rowViews(): List<View> {
        val list = descendants().filterIsInstance<AbsListView>().singleOrNull() ?: return emptyList()
        val adapter = list.adapter ?: return emptyList()
        return (0 until adapter.count).map { adapter.getView(it, null, list) }
    }

    /** The ProgressBars outside the list: the header's. */
    private fun AppWidgetHostView.headerProgressBars(): List<ProgressBar> {
        val list = descendants().filterIsInstance<AbsListView>().singleOrNull()
        return progressBars().filter { bar -> list == null || !list.descendants().contains(bar) }
    }

    private fun View.progressBars(): List<ProgressBar> = descendants().filterIsInstance<ProgressBar>()

    private fun View.texts(): List<String> = descendants().filterIsInstance<android.widget.TextView>().map { it.text.toString() }

    private fun View.descendants(): List<View> = listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()
}
