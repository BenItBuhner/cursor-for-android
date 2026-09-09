package com.cursorforandroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ModelAxis
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.arrangedForPicker
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A fallback row for a follow-up whose current model the catalog cannot show checked — unknown (started elsewhere)
 * or no longer offered. There is no "Default" model: the catalog is the list.
 */
internal data class NoModelRow(val title: String, val subtitle: String?)

/**
 * The composer's model picker: plan-mode / auto-PR, then one clean row per model from `GET /v1/models`. Tapping a
 * model selects it, lifts it to the top of the list and unfolds its parameters — effort, speed, context — under it.
 * Pinning keeps a model at the top after something else is selected. The auto-PR toggle is only shown with an
 * [onAutoCreatePr]: it is a setting of the agent, which a follow-up cannot change.
 *
 * Tapping a model keeps the sheet open so its pickers can be set; dismissing the sheet keeps the last selection.
 *
 * List keys are positional plus id: the API's model ids are unique in practice but nothing guarantees it, and a
 * duplicate key aborts the composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    models: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedVariant: ModelVariant?,
    planMode: Boolean,
    autoCreatePr: Boolean,
    loading: Boolean,
    unavailable: Boolean,
    onPlanMode: (Boolean) -> Unit,
    onAutoCreatePr: ((Boolean) -> Unit)?,
    onRetry: () -> Unit,
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onDismiss: () -> Unit,
    pinnedIds: List<String> = emptyList(),
    onTogglePin: (String) -> Unit = {},
    noModelRow: NoModelRow? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val ordered = remember(models, pinnedIds, selectedModel?.id) { models.arrangedForPicker(pinnedIds, selectedModel?.id) }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        fun pickNone() {
            onSelect(null, null)
            dismiss()
        }
        val listState = rememberLazyListState()
        LaunchedEffect(selectedModel?.id, noModelRow != null, ordered.isNotEmpty()) {
            if (selectedModel == null || ordered.isEmpty()) return@LaunchedEffect
            val offset = (if (noModelRow != null) 1 else 0) + 1 + 1
            listState.scrollToItem(offset)
        }
        SheetHeader("Model") {
            if (loading && models.isNotEmpty()) SpinnerRing(modifier = Modifier.padding(end = 8.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (noModelRow != null) {
                item("current") {
                    SheetRow(title = noModelRow.title, subtitle = noModelRow.subtitle, checked = selectedModel == null) { pickNone() }
                    HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            item("options") {
                SheetSectionLabel("Options")
                OptionRow("Plan mode", planMode, onPlanMode)
                if (onAutoCreatePr != null) OptionRow("Auto-create PR", autoCreatePr, onAutoCreatePr)
                HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            if (models.isEmpty()) {
                when {
                    loading -> item("loading") {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            SpinnerRing()
                            Spacer(Modifier.width(8.dp))
                            Text("Loading models…", style = type.small, color = colors.textQuaternary)
                        }
                    }
                    unavailable -> item("unavailable") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Couldn't load the model list.",
                                style = type.small,
                                color = colors.textQuaternary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            CursorButton("Retry", onClick = onRetry)
                        }
                    }
                }
            } else {
                item("models") { SheetSectionLabel("Models") }
            }
            ordered.forEachIndexed { index, model ->
                item("model-$index-${model.id}") {
                    val selected = model.id == selectedModel?.id
                    val variant = if (selected) selectedVariant ?: model.defaultVariant else model.defaultVariant
                    val axes = remember(model) { model.axes }
                    ModelRow(
                        model = model,
                        selected = selected,
                        pinned = model.id in pinnedIds,
                        onClick = { onSelect(model, variant) },
                        onTogglePin = { onTogglePin(model.id) },
                    )
                    AnimatedVisibility(selected && axes.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        ModelPickers(axes, variant) { axis, value ->
                            model.variantWith(variant, axis.id, value)?.let { onSelect(model, it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable({ onChange(!checked) }, CursorTheme.shapes.base).height(CursorDimens.listRow).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = CursorTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        CursorToggle(checked, onChange)
    }
}

/** One model: its name alone, the accent check when selected, and a pin that keeps it at the top of the list. */
@Composable
private fun ModelRow(
    model: ModelOption,
    selected: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            model.displayName,
            style = type.base,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        PinButton(pinned, model.displayName, onTogglePin)
    }
}

/**
 * The pin control on a model row. Its label sits on the clickable node itself so a tap aimed at it pins rather
 * than selecting the row — for accessibility services and UI tests alike.
 */
@Composable
private fun PinButton(pinned: Boolean, modelName: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    Box(
        Modifier
            .size(width = 40.dp, height = CursorDimens.iconButton)
            .pressable(onClick, CursorTheme.shapes.lg)
            .semantics { contentDescription = if (pinned) "Unpin $modelName" else "Pin $modelName" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CursorIcons.Pin, null, tint = if (pinned) colors.accent else colors.iconTertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * A model's unfolded parameters: a toggle per on/off parameter ("Fast"), a label over a row of choices for the rest
 * ("Effort": Low / Medium / High). [onValue] is called with the parameter and the value the user asked for; which
 * variant that resolves to is the model's business.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPickers(axes: List<ModelAxis>, variant: ModelVariant?, onValue: (ModelAxis, String) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
        axes.forEach { axis ->
            val current = variant?.param(axis.id)
            if (axis.isSwitch) {
                val on = current.equals(axis.onValue, ignoreCase = true)
                fun set(checked: Boolean) = onValue(axis, if (checked) axis.onValue else axis.offValue)
                Row(
                    Modifier.fillMaxWidth().pressable({ set(!on) }, CursorTheme.shapes.base).height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(axis.displayName, style = type.base, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    CursorToggle(on, ::set)
                }
            } else {
                Text(axis.displayName, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    axis.values.forEach { value ->
                        ChoiceChip(value.displayName, selected = value.value == current) { onValue(axis, value.value) }
                    }
                }
            }
        }
    }
}

/** One value of a parameter: a 28dp chip on a 4 % wash, lifted to the 12 % selection fill when it is in force. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    Row(
        Modifier
            .semantics { this.selected = selected }
            .cursorSurface(if (selected) colors.fillActive else colors.fillFaint, if (selected) colors.strokeStrong else colors.strokeSubtle, shape)
            .pressable(onClick, shape, role = Role.RadioButton)
            // The label is sp: at the system's largest font its line alone is taller than 28dp.
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = if (selected) colors.textPrimary else colors.textSecondary, maxLines = 1)
    }
}
