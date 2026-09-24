package com.cursorforandroid.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A launcher's side of a placed widget, for tests: the options it reports for a placement, and what its widget host
 * draws of a widget composed once per size — the composition the platform picks for the space the host is laid out
 * in (`RemoteViews.getRemoteViewsToApply`, which `AppWidgetHostView` calls on layout). Robolectric stands in for
 * `AppWidgetHostView.updateAppWidget` with a plain reapply that cannot take a set of sizes, so the pick is made here,
 * by the platform's own method.
 */
object HomeScreenHost {

    /** The options a launcher reports for a placement from Android 12: the box and the size per orientation. */
    fun launcherOptions(portrait: DpSize, landscape: DpSize): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, portrait.width.value.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, portrait.height.value.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, landscape.width.value.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, landscape.height.value.toInt())
        putParcelableArrayList(
            AppWidgetManager.OPTION_APPWIDGET_SIZES,
            arrayListOf(SizeF(portrait.width.value, portrait.height.value), SizeF(landscape.width.value, landscape.height.value)),
        )
    }

    /** The space a launcher lays a placement out in for [landscape]: the box of that orientation. */
    fun space(options: Bundle, landscape: Boolean): DpSize = if (landscape) {
        DpSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).dp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).dp)
    } else {
        DpSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).dp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).dp)
    }

    /** A widget host drawing [views] as it does laid out at [space]. */
    fun host(context: Context, views: RemoteViews, space: DpSize): AppWidgetHostView =
        AppWidgetHostView(context).also { it.addView(pick(context, views, space).apply(context, it)) }

    /** Of [views] composed per size, the one the platform shows at [space]; a single composition is itself. */
    fun pick(context: Context, views: RemoteViews, space: DpSize): RemoteViews =
        RemoteViews::class.java.getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java)
            .invoke(views, context, SizeF(space.width.value, space.height.value)) as RemoteViews
}
