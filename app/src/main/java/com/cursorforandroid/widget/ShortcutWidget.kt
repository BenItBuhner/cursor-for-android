package com.cursorforandroid.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.domain.ShortcutWidgetSettings
import com.cursorforandroid.ui.quick.QuickComposerActivity
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The shapes the shortcut widget takes, decided by the cells it is given: one cell is the round button; two or more
 * across is the compose bar, a single line of the app's composer; and a bar two or more cells tall is the composer
 * box itself, placeholder above its footer. Resizing the widget moves between them.
 */
enum class ShortcutVariant {
    Button, Bar, TallBar;

    companion object {
        /** Below this the launcher has given one cell; a two-cell widget is at least 110dp on every grid Android ships. */
        private val BAR_MIN_WIDTH = 110.dp
        /** A bar this tall has room for the composer's text line above its footer. */
        private val TALL_MIN_HEIGHT = 96.dp

        fun forSize(size: DpSize): ShortcutVariant = when {
            size.width < BAR_MIN_WIDTH -> Button
            size.height >= TALL_MIN_HEIGHT -> TallBar
            else -> Bar
        }

        /** The cells a shape is previewed at: one cell of a phone's grid for the button, four across for the bar. */
        fun previewSize(variant: ShortcutVariant): DpSize = when (variant) {
            Button -> DpSize(72.dp, 72.dp)
            Bar -> DpSize(320.dp, 72.dp)
            TallBar -> DpSize(320.dp, 140.dp)
        }
    }
}

/**
 * The shortcut widget: a round new-chat button on the home screen, or, given the width, a compose bar that looks like
 * the app's composer. Tapping it opens the quick composer over the launcher ([QuickComposerActivity]) — or, when the
 * widget was pointed elsewhere in its settings, the sidebar's search, one chat, or a Project. Its look and target are
 * per instance ([ShortcutWidgetSettings], in its own Glance state under [SETTINGS_KEY]); its shape follows the cells
 * the launcher gives it ([ShortcutVariant]). Nothing of the account is read for a render: only the app's theme,
 * which a widget whose appearance says "App" follows.
 *
 * Two receivers share it so the picker offers both shapes by name; [previewVariant] is the one each shows there.
 */
abstract class ShortcutWidget(private val previewVariant: ShortcutVariant) : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** One composition per size the launcher reports, so the button and the bar can each be laid out for their cells. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        val appearance = combine(graph.prefs.themeMode, graph.prefs.oledBlack, ::Pair)
        val initial = appearance.first()
        // A theme change while the app is alive reaches this widget the way it reaches the Chats one.
        WidgetSync.follow(context, graph)
        provideContent {
            val settings = ShortcutSettingsState.read(currentState())
            val current by appearance.collectAsState(initial)
            ShortcutWidgetContent(settings, ShortcutVariant.forSize(LocalSize.current), appMode = current.first, appOledBlack = current.second)
        }
    }

    /** The launcher's live preview (Android 15+): the default look, following the system theme like the picker. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { ShortcutWidgetContent(ShortcutWidgetSettings.Default, previewVariant, appMode = ThemeMode.System, appOledBlack = false) }
    }

    companion object {
        /** One shortcut widget's [ShortcutWidgetSettings], as JSON. */
        val SETTINGS_KEY = stringPreferencesKey("shortcut_settings")
    }
}

/** The 1x1 button; grows into the bar when stretched. */
class ShortcutButtonWidget : ShortcutWidget(ShortcutVariant.Button)

/** The compose bar; shrinks to the button when squeezed to one cell. */
class ComposeBarWidget : ShortcutWidget(ShortcutVariant.Bar)

class ShortcutButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutButtonWidget()

    /** The last button was removed: [WidgetSync] keeps following only for a widget of another kind. */
    override fun onDisabled(context: Context) {
        WidgetSync.stopIfNoneLeft(context, ShortcutButtonWidget::class.java)
        super.onDisabled(context)
    }
}

class ComposeBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ComposeBarWidget()

    override fun onDisabled(context: Context) {
        WidgetSync.stopIfNoneLeft(context, ComposeBarWidget::class.java)
        super.onDisabled(context)
    }
}

/** One shortcut widget's settings in its Glance state: the JSON under [ShortcutWidget.SETTINGS_KEY], else the defaults. */
object ShortcutSettingsState {
    fun read(prefs: Preferences): ShortcutWidgetSettings = ShortcutWidgetSettings.decode(prefs[ShortcutWidget.SETTINGS_KEY])

    suspend fun read(context: Context, id: GlanceId): ShortcutWidgetSettings = read(getAppWidgetState(context, PreferencesGlanceStateDefinition, id))

    suspend fun write(context: Context, id: GlanceId, settings: ShortcutWidgetSettings) {
        updateAppWidgetState(context, id) { it[ShortcutWidget.SETTINGS_KEY] = settings.encode() }
    }
}

/** What a shortcut — a widget's tap, a launcher shortcut — launches for each [ShortcutTarget]. */
internal object ShortcutIntents {

    /** The quick composer over the launcher; its own task, so the launcher is what a dismissal returns to. */
    fun quickComposer(context: Context): Intent =
        Intent(Intent.ACTION_MAIN, null, context, QuickComposerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The app with the sidebar's search open. */
    fun search(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun forTarget(context: Context, target: ShortcutTarget): Intent = when (target) {
        ShortcutTarget.NewChat -> quickComposer(context)
        ShortcutTarget.Search -> search(context)
        is ShortcutTarget.Chat -> WidgetIntents.openAgent(context, target.id)
        is ShortcutTarget.Project -> WidgetIntents.openAgent(context, target.id)
    }
}
