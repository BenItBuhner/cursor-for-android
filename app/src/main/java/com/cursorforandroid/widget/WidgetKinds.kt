package com.cursorforandroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.cursorforandroid.AppGraph

/**
 * What one kind of home-screen widget contributes to the shared configuration screen ([WidgetConfigureActivity]):
 * the receiver the launcher names its instances by, a title, and the screen's body for one instance — its preview
 * and its option groups, built from the controls in `WidgetOptionControls.kt`. The Chats widget registers
 * [ChatsWidgetKind]; another widget registers its own the same way and the activity serves both, so the app has one
 * settings screen in one design however many widgets it grows.
 */
interface WidgetKind {
    val receiver: Class<out GlanceAppWidgetReceiver>

    /** The header's title. */
    val title: String

    /** The header's detail line. */
    val subtitle: String

    /**
     * The screen's body: whatever this widget has to show and set. Options apply live — written to the widget's own
     * state as they change — and [WidgetConfigureScope.done] ends the screen with the widget placed.
     */
    @Composable
    fun Configure(scope: WidgetConfigureScope)
}

/** One instance being configured. */
class WidgetConfigureScope(
    val context: Context,
    val graph: AppGraph,
    val glanceId: GlanceId,
    val appWidgetId: Int,
    /** Ends the screen with the widget placed (`RESULT_OK`). */
    val done: () -> Unit,
)

/** The registry the configuration screen resolves a widget's kind from. */
object WidgetKinds {
    private val kinds = LinkedHashMap<String, WidgetKind>()

    init {
        register(ChatsWidgetKind)
    }

    fun register(kind: WidgetKind) {
        synchronized(kinds) { kinds[kind.receiver.name] = kind }
    }

    /** The kind whose receiver the launcher named for a widget, or null for one no kind registered. */
    fun forReceiver(className: String?): WidgetKind? = synchronized(kinds) { className?.let(kinds::get) }

    val all: List<WidgetKind> get() = synchronized(kinds) { kinds.values.toList() }
}
