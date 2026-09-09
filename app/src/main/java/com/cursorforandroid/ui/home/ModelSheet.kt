package com.cursorforandroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ModelAxis
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
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
 * The row of the model picker that stands for sending no `model` at all. What that means depends on where the
 * picker opens: on a new chat it is Cursor's configured default; on a follow-up the chat simply keeps the model it
 * has been running on.
 */
internal data class NoModelRow(val title: String, val subtitle: String?) {
    companion object {
        val Default = NoModelRow("Default", "Your Cursor default model")
    }
}

/**
 * The composer's model picker: the [noModelRow] (null hides it), the plan-mode / auto-PR options, then one row per
 * model from `GET /v1/models`. A model's parameters — effort, speed, context window — are not rows of their own:
 * they unfold under the model as pickers (a toggle for an on/off parameter, a row of choices otherwise), the way the
 * web app's "Options" panel shows them beside the model list. The selected model opens unfolded; any other model's
 * chevron unfolds its pickers, and changing one of them selects that model with the matching variant. The auto-PR
 * toggle is only shown with an [onAutoCreatePr]: it is a setting of the agent, which a follow-up cannot change.
 *
 * Tapping a model row selects it at the variant shown and closes the sheet; the pickers keep the sheet open so
 * several parameters can be set in one go, as the plan-mode / auto-PR toggles do.
 *
 * List keys are positional: the API's model ids are unique in practice but nothing guarantees it, and a duplicate
 * key aborts the composition.
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
    noModelRow: NoModelRow? = NoModelRow.Default,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        // Picking a row plays the sheet's hide animation before the selection is applied.
        fun pick(model: ModelOption?, variant: ModelVariant?) {
            onSelect(model, variant)
            dismiss()
        }
        // The model whose pickers are unfolded: the selected one when the sheet opens, then the last chevron tapped.
        var unfoldedId by remember { mutableStateOf(selectedModel?.id) }
        SheetHeader("Model") {
            if (loading && models.isNotEmpty()) SpinnerRing(modifier = Modifier.padding(end = 8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (noModelRow != null) {
                item("default") {
                    SheetRow(title = noModelRow.title, subtitle = noModelRow.subtitle, checked = selectedModel == null) { pick(null, null) }
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
                                "Couldn't load the model list. Default still works.",
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
            models.forEachIndexed { index, model ->
                item("model-$index") {
                    val selected = model.id == selectedModel?.id
                    // The variant the row stands for: the selection for the selected model, the API's default otherwise.
                    val variant = if (selected) selectedVariant ?: model.defaultVariant else model.defaultVariant
                    val axes = remember(model) { model.axes }
                    val unfolded = axes.isNotEmpty() && unfoldedId == model.id
                    ModelRow(
                        model = model,
                        variant = variant,
                        selected = selected,
                        unfoldable = axes.isNotEmpty(),
                        unfolded = unfolded,
                        onClick = { pick(model, variant) },
                        onToggleUnfolded = { unfoldedId = if (unfolded) null else model.id },
                    )
                    AnimatedVisibility(unfolded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
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

/**
 * One model: its name over the variant's parameters in words ("High effort · Fast"), or its description when the
 * variant has none; the accent check when selected; a chevron to unfold its pickers when it has any.
 */
@Composable
private fun ModelRow(
    model: ModelOption,
    variant: ModelVariant?,
    selected: Boolean,
    unfoldable: Boolean,
    unfolded: Boolean,
    onClick: () -> Unit,
    onToggleUnfolded: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .pressable(onClick, CursorTheme.shapes.base)
            .heightIn(min = CursorDimens.listRow)
            .padding(start = 12.dp, end = if (unfoldable) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.displayName, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = variant?.let { model.qualifier(it) ?: it.description } ?: model.description
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
        if (unfoldable) {
            Spacer(Modifier.width(4.dp))
            UnfoldChevron(unfolded, label = "${if (unfolded) "Hide" else "Show"} ${model.displayName} options", onClick = onToggleUnfolded)
        }
    }
}

/**
 * The chevron that unfolds a model's pickers. Its label sits on the clickable node itself: an icon button whose
 * description lives on the glyph would be merged into the (clickable) row around it, and a tap aimed at the label
 * would pick the row instead — for accessibility services and UI tests alike.
 */
@Composable
private fun UnfoldChevron(unfolded: Boolean, label: String, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (unfolded) 180f else 0f, label = "chevron")
    Box(
        Modifier
            .size(width = 40.dp, height = CursorDimens.iconButton)
            .pressable(onClick, CursorTheme.shapes.lg)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CursorIcons.ChevronDown, null, tint = CursorTheme.colors.iconTertiary, modifier = Modifier.size(CursorDimens.chevron).rotate(rotation))
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
