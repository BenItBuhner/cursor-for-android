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
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.HeaderElement
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
        val host = render(WidgetData.sample(ThemeMode.Dark, now), ChatsWidgetSettings(mode = WidgetMode.Running), refreshing = false)

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
        val atRest = render(WidgetData.sample(ThemeMode.Dark, now), ChatsWidgetSettings(), refreshing = false)
        assertThat(atRest.headerProgressBars()).isEmpty()

        val refreshing = render(WidgetData.sample(ThemeMode.Dark, now), ChatsWidgetSettings(), refreshing = true)
        val spinners = refreshing.headerProgressBars()
        assertThat(spinners).hasSize(1)
        assertThat(spinners.single().isIndeterminate).isTrue()
    }

    @Test
    fun `an empty list that is being fetched says so instead of asking for the app`() {
        val snapshot = WidgetData.sample(ThemeMode.Dark, now).copy(agents = emptyList(), hasLoaded = false)

        val waiting = render(snapshot, ChatsWidgetSettings(), refreshing = true)
        assertThat(waiting.texts()).contains("Loading chats…")

        val idle = render(snapshot, ChatsWidgetSettings(), refreshing = false)
        assertThat(idle.texts()).contains("Open Cursor to load your chats")
    }

    /** The corner button stands for its action, so the header drops the flat button for the same one. */
    @Test
    fun `the corner button takes its action out of the header`() {
        val sample = WidgetData.sample(ThemeMode.Dark, now)
        val newChatCorner = render(sample, ChatsWidgetSettings(cornerAction = CornerAction.NewChat), refreshing = false)
        assertThat(newChatCorner.descriptions()).containsExactly("Choose what the widget lists", "Refresh", "New chat").inOrder()

        val refreshCorner = render(sample, ChatsWidgetSettings(cornerAction = CornerAction.Refresh), refreshing = false)
        assertThat(refreshCorner.descriptions()).containsExactly("Choose what the widget lists", "New chat", "Refresh").inOrder()

        val none = render(sample, ChatsWidgetSettings(cornerAction = CornerAction.None), refreshing = false)
        assertThat(none.descriptions()).containsExactly("Choose what the widget lists", "Refresh", "New chat").inOrder()
        assertThat(none.headerProgressBars()).isEmpty()

        // The cube is off by default; turned on, it leads the header.
        val withLogo = render(sample, ChatsWidgetSettings(cornerAction = CornerAction.None).toggled(HeaderElement.Logo), refreshing = false)
        assertThat(withLogo.descriptions()).containsExactly("Cursor", "Choose what the widget lists", "Refresh", "New chat").inOrder()
    }

    /** A refresh asked for from the corner button turns that button, not the header, into the spinner. */
    @Test
    fun `a corner refresh button spins while its refresh runs`() {
        val sample = WidgetData.sample(ThemeMode.Dark, now)
        val spinning = render(sample, ChatsWidgetSettings(cornerAction = CornerAction.Refresh, cornerStyle = CornerStyle.Glass), refreshing = true)
        assertThat(spinning.headerProgressBars()).hasSize(1)
        assertThat(spinning.descriptions()).doesNotContain("Refresh")
    }

    /** The Glance composition to RemoteViews, the RemoteViews to views under a widget host, as a launcher does it. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(snapshot: WidgetSnapshot, settings: ChatsWidgetSettings, refreshing: Boolean): AppWidgetHostView {
        val size = DpSize(320.dp, 158.dp)
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(app, size) { ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID, refreshing, now) }.remoteViews
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

    /** The content descriptions outside the list, in layout order: the header's controls and the corner button. */
    private fun AppWidgetHostView.descriptions(): List<String> {
        val list = descendants().filterIsInstance<AbsListView>().singleOrNull()
        return descendants().filter { list == null || !list.descendants().contains(it) }.mapNotNull { it.contentDescription?.toString() }
    }

    private fun View.descendants(): List<View> = listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()
}
