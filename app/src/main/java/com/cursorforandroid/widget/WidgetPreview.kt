package com.cursorforandroid.widget

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.view.MotionEvent
import android.widget.RemoteViews
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * picture of one. [size] is the size the widget is drawn at on the home screen ([WidgetPlacement]) where there is a
 * placement to go by. [compose] is called again whenever [key] changes; the last frame stays up until the new one is
 * ready, and the frame's height follows [size] with an animation, so a change of option never leaves a blank or a
 * jump. Taps on the preview go nowhere: the buttons are real, and pressing one here would open the app.
 *
 * A widget wider than the stage is drawn at its own size and scaled down to fit, never laid out narrower: the
 * composition is the one the home screen shows, only smaller. Colours that come in a day / night pair take the side
 * the home screen does ([HomeScreenNight]), which is not always this app's own.
 *
 * The stage's corner is concentric with the widget's: the widget is rounded to the launcher's radius, and the stage
 * sits [stagePadding] outside it with that much more radius. A [framed] widget is drawn in that rounded, shadowed
 * frame; one with no surface of its own — the shortcut widget's disc or bar on the wallpaper — is not, since a shadow
 * around a transparent cell would draw a box the widget does not have.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
fun WidgetPreview(size: DpSize, key: Any, modifier: Modifier = Modifier, framed: Boolean = true, compose: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val stagePadding = 16.dp
    val widgetRadius = remember(context) { with(density) { context.resources.getDimension(R.dimen.widget_corner_radius).toDp() } }
    val homeContext = remember(context) { HomeScreenNight.context(context) }
    val height by animateDpAsState(size.height, tween(260), label = "preview-height")
    var frame by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(key, size) {
        // A change of option is usually one of several in a row; the composition is a few dozen milliseconds, so a
        // short wait folds a burst into one render without the preview ever visibly lagging a tap.
        delay(60)
        frame = runCatching { GlanceRemoteViews().compose(homeContext, size) { compose() }.remoteViews }.getOrNull() ?: frame
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(widgetRadius + stagePadding))
            .background(colors.fillFaint)
            .padding(stagePadding),
        contentAlignment = Alignment.Center,
    ) {
        val scale = if (size.width > maxWidth) maxWidth / size.width else 1f
        Box(Modifier.size(size.width * scale, height * scale).testTag("widget-preview"), contentAlignment = Alignment.Center) {
            val drawn = Modifier.requiredSize(size.width, height).graphicsLayer { scaleX = scale; scaleY = scale }
            Box(
                if (framed) {
                    drawn
                        .shadow(6.dp, RoundedCornerShape(widgetRadius), clip = false, ambientColor = colors.base.copy(alpha = 0.2f), spotColor = colors.base.copy(alpha = 0.2f))
                        .clip(RoundedCornerShape(widgetRadius))
                } else {
                    drawn
                },
            ) {
                val current = frame ?: return@Box
                AndroidView(
                    factory = { PreviewHostView(homeContext) },
                    update = { host -> host.show(current) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Whether the home screen is in night mode — the system's, not this app's. The app sets its own night mode
 * (`AppNightMode`), so its screens can be light on a dark phone; a widget's day / night colours are resolved by the
 * launcher, in the system's mode. Where the system's mode is scheduled rather than on or off, the system resources'
 * configuration is the best word on it.
 */
internal object HomeScreenNight {

    fun isNight(context: Context): Boolean = when (context.getSystemService(UiModeManager::class.java)?.nightMode) {
        UiModeManager.MODE_NIGHT_YES -> true
        UiModeManager.MODE_NIGHT_NO -> false
        else -> Resources.getSystem().configuration.isNight
    }

    /** [context] in the home screen's night mode: itself when the two agree. */
    fun context(context: Context): Context {
        val night = isNight(context)
        val configuration = context.resources.configuration
        if (configuration.isNight == night) return context
        val home = Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return context.createConfigurationContext(home)
    }

    private val Configuration.isNight: Boolean get() = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
