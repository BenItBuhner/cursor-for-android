package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first

/**
 * The "Chats" home-screen widget: recent, running or pinned chats as sidebar rows. What it lists is per instance,
 * kept in the widget's own preferences under [MODE_KEY]; the rows come from the same repository, filters and pins
 * the app renders, restored from disk first so a fresh process shows the last known list at once, and re-rendered
 * by [WidgetSync] whenever any of that changes while the app is alive.
 */
class ChatsWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** One layout for every size: the header keeps its height and the list takes whatever is left. */
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        WidgetData.prepare(graph)
        // This widget exists, so from now on the app's changes must reach it (a no-op once following).
        WidgetSync.follow(context, graph)
        val snapshots = WidgetData.snapshots(graph)
        val initial = snapshots.first()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val mode = WidgetMode.parse(currentState(MODE_KEY))
            // Keeps following the repository for as long as the render session lives (a refresh landing behind a
            // cache-first render, a run finishing); after that, WidgetSync starts a new one.
            val snapshot by snapshots.collectAsState(initial)
            ChatsWidgetContent(snapshot, mode, appWidgetId)
        }
    }

    /**
     * The launcher's live preview (Android 15+): the widget with sample rows. Follows the system theme like the
     * picker around it does, and reads nothing of the account — it has to work before the app was ever opened.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { ChatsWidgetContent(WidgetData.sample(ThemeMode.System), WidgetMode.Default, AppWidgetManager.INVALID_APPWIDGET_ID) }
    }

    companion object {
        /** The [WidgetMode] name of one widget instance. */
        val MODE_KEY = stringPreferencesKey("mode")
    }
}

class ChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChatsWidget()
}

/** What the widget's taps launch. Each is distinct by action or data, so their pending intents never merge. */
internal object WidgetIntents {

    fun openApp(context: Context): Intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's `https://cursor.com/agents/<id>` deep link, as the live notification uses it. */
    fun openAgent(context: Context, agentId: String): Intent =
        Intent(Intent.ACTION_VIEW, CursorEndpoints.webUrl(agentId).toUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun newChat(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Reopens the placement-time choice for one widget; the data URI keeps each widget's intent its own. */
    fun configure(context: Context, appWidgetId: Int): Intent =
        Intent(context, WidgetConfigureActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setData("cursor-widget://configure/$appWidgetId".toUri())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
