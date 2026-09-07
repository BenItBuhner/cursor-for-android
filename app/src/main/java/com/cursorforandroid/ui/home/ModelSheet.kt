package com.cursorforandroid.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The composer's model picker: "Default" (Cursor's configured model), the plan-mode / auto-PR options, then every
 * model from `GET /v1/models` with one row per variant.
 *
 * List keys are positional. The API identifies a variant only by its `id`+`params` combination and reuses the
 * model's display name for each of them ("Composer 2" with `fast` on and off), so a key built from names is not
 * unique and would abort the composition.
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
    onAutoCreatePr: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        fun pick(model: ModelOption?, variant: ModelVariant?) {
            onSelect(model, variant)
            dismiss()
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(CursorDimens.headerHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Model", style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (loading && models.isNotEmpty()) SpinnerRing()
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item("default") {
                    SheetRow(title = "Default", subtitle = "Your Cursor default model", checked = selectedModel == null) { pick(null, null) }
                    HairlineDivider(Modifier.padding(horizontal = 12.dp))
                }
                item("options") {
                    Text("Options", style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                    OptionRow("Plan mode", planMode, onPlanMode)
                    OptionRow("Auto-create PR", autoCreatePr, onAutoCreatePr)
                    HairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                if (models.isEmpty()) {
                    when {
                        loading -> item("loading") {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                SpinnerRing()
                                Spacer(Modifier.width(8.dp))
                                Text("Loading models…", style = type.small, color = colors.textQuaternary)
                            }
                        }
                        unavailable -> item("unavailable") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                }
                models.forEachIndexed { modelIndex, model ->
                    if (model.variants.size <= 1) {
                        val variant = model.variants.firstOrNull()
                        item("model-$modelIndex") {
                            SheetRow(
                                title = model.displayName,
                                subtitle = variant?.let { model.qualifier(it) ?: it.description } ?: model.description,
                                checked = model.id == selectedModel?.id,
                            ) { pick(model, variant) }
                        }
                    } else {
                        item("model-$modelIndex-header") {
                            Text(model.displayName, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                        }
                        model.variants.forEachIndexed { variantIndex, variant ->
                            item("model-$modelIndex-variant-$variantIndex") {
                                SheetRow(
                                    title = variant.displayName,
                                    subtitle = model.qualifier(variant) ?: variant.description ?: model.description,
                                    checked = model.id == selectedModel?.id && variant == selectedVariant,
                                ) { pick(model, variant) }
                            }
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
        Modifier.fillMaxWidth().pressable({ onChange(!checked) }, CursorTheme.shapes.base).height(36.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = CursorTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        CursorToggle(checked, onChange)
    }
}
