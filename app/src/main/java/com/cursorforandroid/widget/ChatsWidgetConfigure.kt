package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.R
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.HeaderElement
import com.cursorforandroid.domain.RowDensity
import com.cursorforandroid.domain.RowElement
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The "Chats" widget's entry in [WidgetKinds]: its settings, live-previewed and written as they change. */
object ChatsWidgetKind : WidgetKind {
    override val receiver: Class<out GlanceAppWidgetReceiver> = ChatsWidgetReceiver::class.java
    override val title: String get() = "Chats widget"
    override val subtitle: String get() = "What it shows and how it looks"

    @OptIn(FlowPreview::class)
    @Composable
    override fun Configure(scope: WidgetConfigureScope) {
        val graph = scope.graph
        // Nothing is drawn until the stored settings are known: a first frame on the defaults that snaps to the
        // chosen values is the jump this screen must not open with.
        var settings by remember { mutableStateOf<ChatsWidgetSettings?>(null) }
        LaunchedEffect(Unit) {
            settings = WidgetSettingsState.read(scope.context, scope.glanceId)
            // A fresh process (the launcher opened this straight from the picker) has the list to restore first.
            WidgetData.prepare(graph, deviceRefreshBudget(scope.context))
        }
        val snapshot by remember { WidgetData.snapshots(graph) }.collectAsStateWithLifecycle(initialValue = null)
        val current = settings ?: return
        val shown = snapshot ?: return
        // Every change lands in the widget's own state at once; the widget itself is redrawn a moment after the
        // last one, so a run of taps is one render and the home screen shows the result the moment this closes.
        val latest by rememberUpdatedState(current)
        LaunchedEffect(Unit) {
            snapshotFlow { latest }.drop(1).collect { WidgetSettingsState.write(scope.context, scope.glanceId, it) }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { latest }.drop(1).debounce(400).collect { WidgetSync.render(scope.context, scope.glanceId) }
        }
        val placement = remember { WidgetPlacement.of(scope.context, scope.appWidgetId) }
        ChatsWidgetOptions(settings = current, snapshot = shown, onChange = { settings = it }, placement = placement)
    }
}

/**
 * The screen's body for one Chats widget: the live preview on its stage, then the option groups. Stateless, so the
 * screenshot tests can draw it with any settings.
 *
 * [placement] is the size the widget is drawn at on the home screen ([WidgetPlacement]); the preview is composed at
 * it, so it shows the arrangement, the rows and the header the home screen does. Without one — nothing reported
 * yet — the preview takes the size the chosen arrangement is best shown at, the 4x2 one for "Auto".
 */
@Composable
fun ChatsWidgetOptions(
    settings: ChatsWidgetSettings,
    snapshot: WidgetSnapshot,
    onChange: (ChatsWidgetSettings) -> Unit,
    modifier: Modifier = Modifier,
    placement: DpSize? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth()) {
        val previewSize = placement ?: WidgetSizes.sizeFor(if (settings.layout == WidgetLayout.Auto) WidgetLayout.Medium else settings.layout)
        WidgetPreview(size = previewSize, key = settings to snapshot, modifier = Modifier.padding(top = 12.dp)) {
            ChatsWidgetContent(snapshot, settings, AppWidgetManager.INVALID_APPWIDGET_ID)
        }

        OptionGroup(stringResource(R.string.widget_configure_layout)) {
            SegmentedControl(WidgetLayout.entries.map { it.label }, settings.layout.ordinal, onSelect = { onChange(settings.copy(layout = WidgetLayout.entries[it])) }, modifier = Modifier.testTag("option-layout"))
        }

        OptionGroup(stringResource(R.string.widget_configure_list)) {
            SegmentedControl(
                WidgetMode.choices.map { it.title },
                WidgetMode.choices.indexOf(settings.mode.choice),
                onSelect = { index -> WidgetMode.choices[index].takeIf { it != settings.mode.choice }?.let { onChange(settings.copy(mode = it)) } },
                modifier = Modifier.testTag("option-list"),
            )
            if (settings.mode.choice == WidgetMode.Projects) {
                ProjectRow(
                    snapshot.projects,
                    projectId = settings.projectId.takeIf { settings.mode == WidgetMode.Project },
                    onPick = { id -> onChange(if (id == null) settings.copy(mode = WidgetMode.Projects) else settings.copy(mode = WidgetMode.Project, projectId = id)) },
                )
                if (!snapshot.projectsAvailable) {
                    Text(
                        stringResource(R.string.widget_configure_projects_extended_mode),
                        style = type.small,
                        color = colors.textQuaternary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("option-projects-extended-mode"),
                    )
                }
            }
        }

        OptionGroup(stringResource(R.string.widget_configure_appearance)) {
            WidgetAppearanceOptions(settings.appearance, onChange = { onChange(settings.copy(appearance = it)) })
        }

        OptionGroup(stringResource(R.string.widget_configure_header), modifier = Modifier.testTag("option-header")) {
            // A button the corner button already stands for is not drawn twice, so it is not offered either.
            HeaderElement.entries.filterNot { settings.cornerStandsFor(it) }.forEach { element ->
                OptionToggle(element.label, checked = settings.shows(element), onChange = { onChange(settings.toggled(element)) }, detail = element.detail)
            }
            OptionToggle(
                stringResource(R.string.widget_configure_header_auto_hide),
                checked = settings.headerAutoHide,
                onChange = { onChange(settings.copy(headerAutoHide = it)) },
                detail = stringResource(R.string.widget_configure_header_auto_hide_detail, WidgetSizes.HEADER_MIN_HEIGHT.value.toInt()),
            )
        }

        OptionGroup(stringResource(R.string.widget_configure_rows)) {
            SegmentedControl(RowDensity.entries.map { it.label }, settings.density.ordinal, onSelect = { onChange(settings.copy(density = RowDensity.entries[it])) }, modifier = Modifier.testTag("option-density"))
            RowElement.entries.forEach { element ->
                OptionToggle(element.label, checked = settings.shows(element), onChange = { onChange(settings.toggled(element)) })
            }
        }

        OptionGroup(stringResource(R.string.widget_configure_corner)) {
            OptionLabel(stringResource(R.string.widget_configure_corner_action))
            ChipRow(CornerAction.entries.map { it.label }, settings.cornerAction.ordinal, onSelect = { onChange(settings.copy(cornerAction = CornerAction.entries[it])) }, modifier = Modifier.testTag("option-corner-action"))
            if (settings.cornerAction != CornerAction.None) {
                OptionLabel(stringResource(R.string.widget_configure_corner_style))
                SwatchRow(CornerStyle.entries, settings.cornerStyle, onSelect = { onChange(settings.copy(cornerStyle = it)) }, label = { it.label }) { style ->
                    val glyph = when (settings.cornerAction) {
                        CornerAction.Refresh -> CursorIcons.Refresh
                        CornerAction.Search -> CursorIcons.Search
                        CornerAction.OpenApp -> CursorIcons.Cube
                        else -> CursorIcons.Plus
                    }
                    when (style) {
                        CornerStyle.White -> RoundButtonSwatch(Color.White, glyph, CursorLightColors.iconPrimary)
                        CornerStyle.Tinted -> RoundButtonSwatch(colors.accent, glyph, colors.onAccent)
                        CornerStyle.Glass -> RoundButtonSwatch(colors.fillMedium, glyph, colors.iconSecondary)
                    }
                }
            }
        }

        val note = stringResource(if (settings.mode == WidgetMode.Projects) R.string.widget_configure_note_projects else R.string.widget_configure_note)
        val shownLayout = if (settings.layout == WidgetLayout.Auto) WidgetSizes.layoutFor(previewSize) else settings.layout
        val titleShown = shownLayout != WidgetLayout.Small && WidgetSizes.showsHeader(previewSize, settings) && HeaderElement.Title in settings.headerParts
        val back = stringResource(if (titleShown) R.string.widget_configure_back_title else R.string.widget_configure_back_long_press)
        Text(
            "$note $back",
            style = type.small,
            color = colors.textQuaternary,
            modifier = Modifier.padding(top = 12.dp, start = 2.dp),
        )
    }
}

/**
 * Theme and opacity — the two settings every widget kind shares ([WidgetAppearance]), as one group's rows, for any
 * kind's [WidgetKind.Configure] to place.
 */
@Composable
fun WidgetAppearanceOptions(appearance: WidgetAppearance, onChange: (WidgetAppearance) -> Unit) {
    OptionLabel(stringResource(R.string.widget_configure_theme))
    ChipRow(WidgetTheme.entries.map { it.label }, appearance.theme.ordinal, onSelect = { onChange(appearance.copy(theme = WidgetTheme.entries[it])) }, modifier = Modifier.testTag("option-theme"))
    OptionLabel(stringResource(R.string.widget_configure_opacity))
    OptionSlider(appearance.opacity, WidgetAppearance.MIN_OPACITY..100, step = 5, onChange = { onChange(appearance.copy(opacity = it)) }, modifier = Modifier.testTag("option-opacity")) { "$it%" }
}

/**
 * Which Projects the widget lists: all of them ([WidgetMode.Projects], a null [projectId]) or one Project's chats
 * ([WidgetMode.Project]), and a sheet to pick between them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectRow(projects: List<Agent>, projectId: String?, onPick: (String?) -> Unit) {
    val colors = CursorTheme.colors
    var open by remember { mutableStateOf(false) }
    val chosen = projects.firstOrNull { it.id == projectId }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({ open = true }, RoundedCornerShape(WidgetOptionRadii.control))
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("option-project"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (projectId == null) {
            Icon(CursorIcons.Layers, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
        } else {
            Icon(CursorIcons.project(chosen?.projectAppearance?.icon), null, tint = colors.projectTone(chosen?.projectAppearance?.colorId), modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                projectId == null -> stringResource(R.string.widget_configure_projects_all)
                chosen != null -> chosen.name
                else -> stringResource(R.string.widget_configure_project_none)
            },
            style = CursorTheme.typography.base,
            color = if (projectId == null || chosen != null) colors.textPrimary else colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
    if (open) {
        CursorSheet(onDismiss = { open = false }) { dismiss ->
            SheetHeader(stringResource(R.string.widget_configure_project))
            ProjectChoice(stringResource(R.string.widget_configure_projects_all), picked = projectId == null, onClick = { onPick(null); dismiss() }) {
                Icon(CursorIcons.Layers, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp))
            }
            if (projects.isEmpty()) {
                Text("No Projects in the list yet", style = CursorTheme.typography.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            projects.forEach { project ->
                ProjectChoice(project.name, picked = project.id == projectId, onClick = { onPick(project.id); dismiss() }) {
                    Icon(CursorIcons.project(project.projectAppearance?.icon), null, tint = colors.projectTone(project.projectAppearance?.colorId), modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun ProjectChoice(label: String, picked: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (picked) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}
