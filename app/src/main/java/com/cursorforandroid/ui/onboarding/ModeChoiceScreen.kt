package com.cursorforandroid.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.settings.ExtendedModeAcknowledgmentDialog
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * Every word on the choice screen, so it can be quoted and tested verbatim. Two options stated in the same voice and
 * at the same length: what each uses, and what that means for the account. Nothing here recommends either.
 */
object ModeChoiceCopy {
    const val TITLE = "Choose a mode"
    const val LEAD = "Two ways this app can talk to Cursor."

    const val SDK_ONLY_TITLE = "SDK only"
    const val SDK_ONLY_BODY =
        "Uses only Cursor's documented Cloud Agents API — everything Cursor officially supports for third-party apps. No risk to your account."

    const val EXTENDED_TITLE = "Extended mode"
    const val EXTENDED_BODY =
        "The full cloud experience — Projects, steering, queue, workspace files, remote desktop and more — through undocumented endpoints. " +
            "This is against Cursor's Terms of Service and can get an account restricted."

    const val CONTINUE = "Continue"

    /** Shown once the warning has been declined: the choice fell back, and where it can be changed. */
    const val DECLINED_NOTE = "Staying on SDK only. You can change this later in Settings."
}

/** Test tags, for the tests that drive the screen. */
object ModeChoiceTags {
    const val SCREEN = "mode_choice_screen"
    const val SDK_ONLY = "mode_choice_sdk_only"
    const val EXTENDED = "mode_choice_extended"
    const val CONTINUE = "mode_choice_continue"
    const val NOTE = "mode_choice_note"
}

/**
 * The one screen between a sign-in and the app: SDK only or Extended mode, as two options of equal weight with SDK
 * only selected to begin with, and one Continue button that reads the same whichever is selected.
 *
 * Continuing with SDK only settles the choice at once. Continuing with Extended mode opens the same acknowledgment
 * dialog the Settings toggle opens — the warning in full, the checkbox, Turn on — and settles the choice on its
 * confirm, recorded exactly as Settings records it; when the warning is already on record from Settings, it is not
 * asked again, as Settings does not ask again. Cancelling the dialog selects SDK only back and says so in one line.
 */
@Composable
fun ModeChoiceScreen(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val acknowledgedAt by graph.extendedMode.acknowledgedAt.collectAsStateWithLifecycle(initialValue = null)
    var extendedSelected by rememberSaveable { mutableStateOf(false) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var declined by rememberSaveable { mutableStateOf(false) }
    // Not saveable: a recreation mid-write composes the screen again and the write it was waiting on lands regardless.
    var busy by remember { mutableStateOf(false) }

    fun settle(choice: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { choice() }
    }

    fun continueWithSelection() {
        when {
            !extendedSelected -> settle { graph.onboarding.chooseSdkOnly() }
            acknowledgedAt != null -> settle { graph.onboarding.chooseExtended() }
            else -> dialogOpen = true
        }
    }

    Box(
        Modifier.fillMaxSize().background(colors.canvas).systemBarsPadding().semantics { testTag = ModeChoiceTags.SCREEN },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().fadingVerticalScroll(surface = colors.canvas).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(CursorIcons.Cube, null, tint = colors.iconPrimary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(22.dp))
            Text(ModeChoiceCopy.TITLE, style = type.pageTitle, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(ModeChoiceCopy.LEAD, style = type.base, color = colors.textSecondary)
            Spacer(Modifier.height(22.dp))

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeOption(
                    title = ModeChoiceCopy.SDK_ONLY_TITLE,
                    body = ModeChoiceCopy.SDK_ONLY_BODY,
                    selected = !extendedSelected,
                    enabled = !busy,
                    onSelect = { extendedSelected = false },
                    modifier = Modifier.semantics { testTag = ModeChoiceTags.SDK_ONLY },
                )
                ModeOption(
                    title = ModeChoiceCopy.EXTENDED_TITLE,
                    body = ModeChoiceCopy.EXTENDED_BODY,
                    selected = extendedSelected,
                    enabled = !busy,
                    onSelect = { extendedSelected = true },
                    modifier = Modifier.semantics { testTag = ModeChoiceTags.EXTENDED },
                )
            }

            if (declined) {
                Spacer(Modifier.height(14.dp))
                Text(
                    ModeChoiceCopy.DECLINED_NOTE,
                    style = type.small,
                    color = colors.textTertiary,
                    modifier = Modifier.semantics { testTag = ModeChoiceTags.NOTE },
                )
            }

            Spacer(Modifier.height(22.dp))
            CursorButton(
                ModeChoiceCopy.CONTINUE,
                onClick = ::continueWithSelection,
                primary = true,
                enabled = !busy,
                height = 40.dp,
                modifier = Modifier.fillMaxWidth().semantics { testTag = ModeChoiceTags.CONTINUE },
            )
        }
    }

    if (dialogOpen) {
        ExtendedModeAcknowledgmentDialog(
            onCancel = {
                dialogOpen = false
                extendedSelected = false
                declined = true
            },
            onConfirm = {
                dialogOpen = false
                settle { graph.onboarding.chooseExtended() }
            },
        )
    }
}

/**
 * One option: a radio mark, the name and what it means, on a card whose stroke says whether it is the selection. The
 * two are drawn by the same code with the same styles, so neither can look like the one to pick.
 */
@Composable
private fun ModeOption(
    title: String,
    body: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, if (selected) colors.strokeStrong else colors.strokeSubtle, shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ripple(color = colors.base, bounded = true),
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioMark(selected = selected, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.title, color = colors.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, style = type.small, color = colors.textSecondary)
        }
    }
}

/** A 16dp ring; filled at the centre while selected. The card it sits on carries the selection semantics. */
@Composable
private fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Box(
        modifier.size(16.dp).border(if (selected) 1.5.dp else CursorDimens.hairline, if (selected) colors.accent else colors.strokeStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
    }
}
