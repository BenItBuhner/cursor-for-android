package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.os.Looper
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sizes a placed widget is composed and drawn at, read from the options a launcher reports — the one source the
 * widget's renders and its settings screen's preview both take their size from — and the platform's own choice of
 * composition for the space a host lays the widget out in, which [WidgetPlacement.bestFit] has to agree with.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class WidgetPlacementTest {

    @Test
    fun `the reported sizes are the sizes, and the box of the orientation picks among them`() {
        val options = launcherOptions(portrait = DpSize(338.dp, 176.dp), landscape = DpSize(610.dp, 110.dp))
        assertThat(WidgetPlacement.sizes(options)).containsExactly(DpSize(338.dp, 176.dp), DpSize(610.dp, 110.dp)).inOrder()
        assertThat(WidgetPlacement.current(options, landscape = false)).isEqualTo(DpSize(338.dp, 176.dp))
        assertThat(WidgetPlacement.current(options, landscape = true)).isEqualTo(DpSize(610.dp, 110.dp))
    }

    @Test
    fun `before sizes were reported, the box is the portrait and the landscape size`() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 338)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 380)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 610)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240)
        }
        assertThat(WidgetPlacement.sizes(options)).containsExactly(DpSize(338.dp, 380.dp), DpSize(610.dp, 240.dp)).inOrder()
        assertThat(WidgetPlacement.current(options, landscape = false)).isEqualTo(DpSize(338.dp, 380.dp))
        assertThat(WidgetPlacement.current(options, landscape = true)).isEqualTo(DpSize(610.dp, 240.dp))
    }

    @Test
    fun `sizes without a box go by their shape, and nothing reported is no placement`() {
        val options = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(610f, 110f), SizeF(338f, 176f)))
        }
        assertThat(WidgetPlacement.current(options, landscape = false)).isEqualTo(DpSize(338.dp, 176.dp))
        assertThat(WidgetPlacement.current(options, landscape = true)).isEqualTo(DpSize(610.dp, 110.dp))
        assertThat(WidgetPlacement.sizes(Bundle())).isEmpty()
        assertThat(WidgetPlacement.current(Bundle(), landscape = false)).isNull()
    }

    /** A foldable reports a size per screen; the box of the screen in use picks the nearest that fits, not the largest. */
    @Test
    fun `of several sizes that fit, the nearest is the one drawn`() {
        val candidates = listOf(DpSize(110.dp, 40.dp), DpSize(250.dp, 40.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 230.dp))
        // The bucket arrangement this replaced: a 4x2 box is nearer a wide one-line size than the 4x2 one.
        assertThat(WidgetPlacement.bestFit(DpSize(320.dp, 158.dp), candidates)).isEqualTo(DpSize(250.dp, 40.dp))
        assertThat(WidgetPlacement.bestFit(DpSize(350.dp, 420.dp), candidates)).isEqualTo(DpSize(250.dp, 230.dp))
        // A size a dp over the space still fits once the space is rounded up; two over does not.
        assertThat(WidgetPlacement.bestFit(DpSize(179.4.dp, 110.dp), listOf(DpSize(180.dp, 110.dp)))).isEqualTo(DpSize(180.dp, 110.dp))
        assertThat(WidgetPlacement.bestFit(DpSize(178.dp, 110.dp), listOf(DpSize(180.dp, 110.dp)))).isNull()
    }

    /**
     * The platform's own choice ([HomeScreenHost]): one composition per size, each saying which it is, drawn at the
     * space of a placement. [WidgetPlacement.bestFit] names the one shown.
     */
    @Test
    fun `bestFit is the composition a widget host shows`() {
        val candidates = listOf(DpSize(110.dp, 40.dp), DpSize(170.dp, 40.dp), DpSize(250.dp, 40.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 230.dp), DpSize(338.dp, 176.dp))
        val spaces = listOf(DpSize(320.dp, 158.dp), DpSize(338.dp, 176.dp), DpSize(350.dp, 420.dp), DpSize(200.dp, 60.dp), DpSize(600.dp, 120.dp), DpSize(100.dp, 30.dp))
        spaces.forEach { space ->
            val shown = hostShows(candidates, space)
            assertThat(shown).isEqualTo(WidgetPlacement.bestFit(space, candidates)?.label() ?: candidates.minBy { it.width.value * it.height.value }.label())
        }
    }

    private fun hostShows(candidates: List<DpSize>, space: DpSize): String {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val views = RemoteViews(candidates.associate { SizeF(it.width.value, it.height.value) to labelled(activity.packageName, it.label()) })
        val host = HomeScreenHost.host(activity, views, space)
        val density = activity.resources.displayMetrics.density
        activity.setContentView(host, ViewGroup.LayoutParams((space.width.value * density).toInt(), (space.height.value * density).toInt()))
        shadowOf(Looper.getMainLooper()).idle()
        return host.texts().single()
    }

    /** A composition that is one text, its size; the framework's one-line item is a TextView a RemoteViews may inflate. */
    private fun labelled(packageName: String, label: String): RemoteViews =
        RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, label) }

    private fun DpSize.label(): String = "${width.value.toInt()}x${height.value.toInt()}"

    private fun View.texts(): List<String> = descendants().filterIsInstance<TextView>().map { it.text.toString() }

    private fun View.descendants(): List<View> = listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()

    private fun launcherOptions(portrait: DpSize, landscape: DpSize): Bundle = HomeScreenHost.launcherOptions(portrait, landscape)
}
