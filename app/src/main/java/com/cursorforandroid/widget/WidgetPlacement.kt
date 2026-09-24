package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Where the launcher has put one widget, read from the options it reports for it: the one source both a widget's
 * renders and its settings screen's preview take their size from, so that the preview is the widget at the size the
 * home screen shows it — not at a size of the preview's own choosing, which is how the two came to disagree.
 *
 * The launcher reports the sizes it draws the widget at — one per orientation, and per screen on a foldable — as
 * [AppWidgetManager.OPTION_APPWIDGET_SIZES] from Android 12, and before that as a box: portrait is the minimum width
 * by the maximum height, landscape the maximum width by the minimum height. A widget in `SizeMode.Exact` is composed
 * once for each of [sizes]; the launcher's host then shows the composition that fits the space it lays the widget
 * out in best ([bestFit]).
 */
object WidgetPlacement {

    /**
     * The sizes an exact-size widget is composed for, read as Glance reads them: the reported sizes, else the portrait
     * and landscape sizes of the box. Empty when the launcher has reported neither.
     */
    fun sizes(options: Bundle): List<DpSize> {
        val reported = if (Build.VERSION.SDK_INT >= 31) reportedSizes(options) else emptyList()
        if (reported.isNotEmpty()) return reported
        val box = Box.of(options) ?: return emptyList()
        return listOf(box.portrait, box.landscape)
    }

    /**
     * The size the widget is drawn at while the screen is [landscape] or not: the composition the host picks for the
     * box of that orientation. Null when the launcher has reported nothing to go by.
     */
    fun current(options: Bundle, landscape: Boolean): DpSize? {
        val all = sizes(options)
        if (all.isEmpty()) return null
        val box = Box.of(options)?.let { if (landscape) it.landscape else it.portrait }
        if (box != null) return bestFit(box, all) ?: box
        // Sizes without a box: the widest one is the landscape one, the tallest the portrait one.
        return if (landscape) all.maxBy { it.width / it.height } else all.minBy { it.width / it.height }
    }

    /** [current] for one placed widget, in the orientation the screen is in now; null for no real placement. */
    fun of(context: Context, appWidgetId: Int): DpSize? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val options = runCatching { AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId) }.getOrNull() ?: return null
        return current(options, landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    /**
     * The composition a host of [available] size shows, as `RemoteViews` chooses among sized layouts: of those that
     * fit — a size fits when it exceeds the space by less than a dp after rounding the space up — the one nearest by
     * distance, not the largest; null when none fits (the platform then shows the smallest).
     */
    fun bestFit(available: DpSize, candidates: Collection<DpSize>): DpSize? = candidates
        .filter { ceil(available.width.value) + 1 > it.width.value && ceil(available.height.value) + 1 > it.height.value }
        .minByOrNull { (it.width.value - available.width.value).squared() + (it.height.value - available.height.value).squared() }

    private fun Float.squared(): Float = this * this

    @RequiresApi(31)
    private fun reportedSizes(options: Bundle): List<DpSize> {
        @Suppress("DEPRECATION")
        val sizes: List<SizeF>? = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        return sizes.orEmpty().map { DpSize(it.width.dp, it.height.dp) }
    }

    /** The pre-Android-12 box; all four edges or none, as Glance takes it. */
    private class Box(val portrait: DpSize, val landscape: DpSize) {
        companion object {
            fun of(options: Bundle): Box? {
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                if (minWidth == 0 || maxWidth == 0 || minHeight == 0 || maxHeight == 0) return null
                return Box(portrait = DpSize(minWidth.dp, maxHeight.dp), landscape = DpSize(maxWidth.dp, minHeight.dp))
            }
        }
    }
}
