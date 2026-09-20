package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.R
import com.cursorforandroid.appGraph
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

/**
 * The settings of one home-screen widget. The launcher opens this when a widget is placed (and, from Android 9,
 * from its reconfigure affordance); a widget's own title opens it too. Which widget kind it is comes from the
 * receiver the launcher names for the instance ([WidgetKinds]); the kind supplies the body — its preview and its
 * options, which apply as they change — and this activity the chrome around it: the header, Done, and the result
 * the launcher waits for. Backing out leaves a widget being placed unplaced, as the platform expects.
 */
class WidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val graph = appGraph

        setContent {
            // Nothing is drawn until the widget is known to the system and the theme is known: a first frame in the
            // default theme that flips to the chosen one is a jump. The window behind it already wears the app's
            // night mode.
            val appearance by remember { combine(graph.prefs.themeMode, graph.prefs.oledBlack, ::Pair) }.collectAsStateWithLifecycle(initialValue = null)
            val bound by produceState<Bound?>(null) { value = awaitBinding(appWidgetId) ?: return@produceState finish() }
            val (themeMode, oledBlack) = appearance ?: return@setContent
            val (glanceId, kind) = bound ?: return@setContent
            val scope = remember(glanceId) {
                WidgetConfigureScope(this, graph, glanceId, appWidgetId) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    // The options were written as they changed; the render runs off the process's scope, so finishing
                    // here cannot cut it short.
                    WidgetSync.render(this, glanceId)
                    finish()
                }
            }
            CursorTheme(mode = themeMode, oledBlack = oledBlack) {
                WidgetConfigureScreen(title = kind.title, subtitle = kind.subtitle, onDone = scope.done, onClose = { finish() }) {
                    kind.Configure(scope)
                }
            }
        }
    }

    private class Bound(val glanceId: GlanceId, val kind: WidgetKind) {
        operator fun component1() = glanceId
        operator fun component2() = kind
    }

    /**
     * The widget as the system knows it: its provider (which names its kind) and its Glance id. The launcher binds
     * the id to the provider before it opens this screen, but the binding is the system's to report and a launcher
     * under load has been seen to open the screen a beat before the service would say so; asked again for a moment
     * rather than taken for an error, since the alternative — Glance's `getGlanceIdBy` throwing — took the screen and
     * the placement with it. Null when the id never becomes this app's, in which case there is nothing to configure.
     */
    private suspend fun awaitBinding(appWidgetId: Int): Bound? {
        val manager = AppWidgetManager.getInstance(this)
        val glance = GlanceAppWidgetManager(this)
        repeat(BIND_ATTEMPTS) { attempt ->
            val info = runCatching { manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
            if (info != null) {
                val glanceId = runCatching { glance.getGlanceIdBy(appWidgetId) }.getOrNull()
                if (glanceId != null) return Bound(glanceId, WidgetKinds.forReceiver(info.provider.className) ?: ChatsWidgetKind)
            }
            if (attempt == 0) Log.w(TAG, "Widget $appWidgetId is not bound to this app yet; waiting for the launcher")
            delay(BIND_RETRY_MS)
        }
        Log.w(TAG, "Widget $appWidgetId never became this app's; nothing to configure")
        return null
    }

    private companion object {
        const val TAG = "WidgetConfigure"
        const val BIND_ATTEMPTS = 20
        const val BIND_RETRY_MS = 150L
    }
}

/** The chrome of the settings screen: header with Close and Done, then the kind's body in a scrolling column. */
@Composable
fun WidgetConfigureScreen(title: String, subtitle: String, onDone: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, body: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = title,
            subtitle = subtitle,
            leading = { FlatIconButton(CursorIcons.Close, "Close", onClick = onClose) },
            trailing = {
                Text(
                    stringResource(R.string.widget_configure_done),
                    style = CursorTheme.typography.baseMedium,
                    color = colors.link,
                    modifier = Modifier.pressable(onDone, CursorTheme.shapes.base).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            },
        )
        Column(Modifier.fillMaxSize().navigationBarsPadding().fadingVerticalScroll(surface = colors.canvas).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            body()
        }
    }
}
