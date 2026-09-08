package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.R
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Chooses what one widget lists. The launcher opens this when the widget is placed (and, from Android 9, from its
 * reconfigure affordance); the widget's own title opens it too. Picking a row applies at once, like the app's
 * picker sheets; backing out leaves a widget being placed unplaced, as the platform expects.
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
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)

        setContent {
            val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            // Null until the widget's stored choice has been read: a fresh widget starts on the default.
            var selected by remember { mutableStateOf<WidgetMode?>(null) }
            LaunchedEffect(Unit) {
                selected = WidgetMode.parse(getAppWidgetState(this@WidgetConfigureActivity, PreferencesGlanceStateDefinition, glanceId)[ChatsWidget.MODE_KEY])
            }
            CursorTheme(mode = themeMode, oledBlack = oledBlack) {
                WidgetConfigureScreen(
                    selected = selected,
                    onPick = { mode ->
                        selected = mode
                        lifecycleScope.launch {
                            updateAppWidgetState(this@WidgetConfigureActivity, glanceId) { it[ChatsWidget.MODE_KEY] = mode.name }
                            ChatsWidget().update(this@WidgetConfigureActivity, glanceId)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

/** Settings-page idiom: header, a 12sp group label, a bordered card of rows with the accent check on the chosen one. */
@Composable
fun WidgetConfigureScreen(selected: WidgetMode?, onPick: (WidgetMode) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = stringResource(R.string.widget_configure_title),
            subtitle = stringResource(R.string.widget_configure_subtitle),
            leading = { FlatIconButton(CursorIcons.Close, "Close", onClick = onClose) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.widget_configure_group),
                style = type.small,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp),
            )
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                WidgetMode.entries.forEachIndexed { index, mode ->
                    ModeRow(mode, icon = mode.icon(), checked = selected == mode, onClick = { onPick(mode) })
                    if (index != WidgetMode.entries.lastIndex) HairlineDivider(Modifier.padding(horizontal = 14.dp))
                }
            }
            Text(
                stringResource(R.string.widget_configure_note),
                style = type.small,
                color = colors.textQuaternary,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )
        }
    }
}

private fun WidgetMode.icon(): ImageVector = when (this) {
    WidgetMode.Recent -> CursorIcons.Clock
    WidgetMode.Running -> CursorIcons.Sparkle
    WidgetMode.Pinned -> CursorIcons.Pin
}

/** A sheet-style row: 17dp glyph at 66 %, label with a 12sp detail line, accent check when chosen. */
@Composable
private fun ModeRow(mode: WidgetMode, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, CursorTheme.shapes.lg)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mode.label, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mode.detail, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}
