package com.cursorforandroid.ui.settings

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode

object ThemeSwitchCopy {
    const val TITLE = "Theme"

    fun label(mode: ThemeMode): String = when (mode) {
        ThemeMode.System -> "Auto"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }
}

object ThemeSwitchTags {
    const val SWITCH = "settings_theme_switch"

    fun of(mode: ThemeMode): String = "settings_theme_${mode.name.lowercase()}"
}

/** The theme as one switch, its options in the order a reader scans them: Auto, Light, Dark. */
@Composable
internal fun ThemeSwitch(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    SegmentedSwitch(
        options = ThemeOrder,
        selected = selected,
        label = ThemeSwitchCopy::label,
        onSelect = onSelect,
        modifier = modifier.testTag(ThemeSwitchTags.SWITCH),
        optionTag = ThemeSwitchTags::of,
    )
}

private val ThemeOrder = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)

/**
 * A switch with more than two positions: a track of equal segments, each labelled inside, and a thumb under the
 * chosen one that slides to the next choice rather than jumping. Square-ish like the app's buttons, not a pill; the
 * thumb's corner is the track's less the inset between them, so the two stay concentric. Read as a group of radio
 * buttons, each by its label.
 */
@Composable
internal fun <T> SegmentedSwitch(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionTag: ((T) -> String)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0)
    val thumbOffset by animateDpAsState(SegmentWidth * index, tween(SlideMillis, easing = SlideEasing), label = "thumb")
    val thumb = if (colors.isDark) colors.fillMedium else colors.elevated
    val thumbBorder = if (colors.isDark) colors.strokeSubtle else colors.stroke
    Box(
        modifier
            .height(ControlHeight)
            .width(SegmentWidth * options.size + SwitchInset * 2)
            .background(colors.fill, CursorTheme.shapes.base)
            .padding(SwitchInset),
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(SegmentWidth)
                .fillMaxHeight()
                .background(thumb, CursorTheme.shapes.sm)
                .border(CursorDimens.hairline, thumbBorder, CursorTheme.shapes.sm),
        )
        Row(Modifier.selectableGroup()) {
            options.forEach { option ->
                val chosen = option == selected
                // The selection animates, not the colour: a theme change (often the very tap on this switch) repaints the labels at once.
                val emphasis by animateFloatAsState(if (chosen) 1f else 0f, tween(SlideMillis, easing = SlideEasing), label = "label")
                val text = lerp(colors.textTertiary, colors.textPrimary, emphasis)
                Box(
                    Modifier
                        .width(SegmentWidth)
                        .fillMaxHeight()
                        .selectable(
                            selected = chosen,
                            onClick = {
                                if (!chosen) {
                                    haptics.perform(Haptic.Select)
                                    onSelect(option)
                                }
                            },
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        )
                        .then(if (optionTag != null) Modifier.testTag(optionTag(option)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = type.baseMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val SwitchInset = 2.dp
private val SegmentWidth = 56.dp
private const val SlideMillis = 320

/** Quick off the mark and a long, soft landing: the thumb reads as thrown to its place, not driven there at one speed. */
private val SlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
