package com.cursorforandroid.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.widget.RemoteViews
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.cursorforandroid.R
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay

/**
 * The widget as the launcher will draw it, on a stage in the configuration screen: the same Glance composition,
 * translated to RemoteViews and applied under an `AppWidgetHostView` — so what is previewed is the widget, not a
 * picture of one. [compose] is called again whenever [key] changes; the last frame stays up until the new one is
 * ready, and the frame's height follows [size] with an animation, so a change of option never leaves a blank or a
 * jump. Taps on the preview go nowhere: the buttons are real, and pressing one here would open the app.
 *
 * The stage's corner is concentric with the widget's: the widget is rounded to the launcher's radius, and the stage
 * sits [stagePadding] outside it with that much more radius.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
fun WidgetPreview(size: DpSize, key: Any, modifier: Modifier = Modifier, compose: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val stagePadding = 16.dp
    val widgetRadius = remember(context) { with(density) { context.resources.getDimension(R.dimen.widget_corner_radius).toDp() } }
    val height by animateDpAsState(size.height, tween(260), label = "preview-height")
    var frame by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(key, size) {
        // A change of option is usually one of several in a row; the composition is a few dozen milliseconds, so a
        // short wait folds a burst into one render without the preview ever visibly lagging a tap.
        delay(60)
        frame = runCatching { GlanceRemoteViews().compose(context, size) { compose() }.remoteViews }.getOrNull() ?: frame
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(widgetRadius + stagePadding))
            .background(colors.fillFaint)
            .padding(stagePadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = size.width)
                .fillMaxWidth()
                .height(height)
                .shadow(6.dp, RoundedCornerShape(widgetRadius), clip = false, ambientColor = colors.base.copy(alpha = 0.2f), spotColor = colors.base.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(widgetRadius))
                .testTag("widget-preview"),
        ) {
            val current = frame ?: return@Box
            AndroidView(
                factory = { PreviewHostView(it) },
                update = { host -> host.show(current) },
                modifier = Modifier.size(size.width, height),
            )
        }
    }
}

/**
 * A widget host for the preview: applies each frame as a launcher would, and swallows touches so the widget's real
 * pending intents are not fired from inside the settings screen. The list adapter of a RemoteViews collection is
 * applied inline from Android 12; before that it is served by a `RemoteViewsService` a host has to be bound to, so
 * the rows stay empty on those versions and the header alone previews.
 */
@SuppressLint("ViewConstructor")
internal class PreviewHostView(context: Context) : AppWidgetHostView(context) {
    private var shown: RemoteViews? = null

    fun show(views: RemoteViews) {
        if (views === shown) return
        shown = views
        removeAllViews()
        runCatching { addView(views.apply(context, this)) }
        // A launcher's list shows its scrollbar only while scrolling; the preview never scrolls.
        listViews(this).forEach { it.isVerticalScrollBarEnabled = false }
    }

    private fun listViews(view: android.view.View): List<android.widget.AbsListView> = when (view) {
        is android.widget.AbsListView -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { listViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    override fun onTouchEvent(event: MotionEvent?): Boolean = true

    companion object {
        /** Whether the rows of a list preview are drawn on this version (see the class note). */
        val listsPreview: Boolean get() = Build.VERSION.SDK_INT >= 31
    }
}
