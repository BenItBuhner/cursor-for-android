package com.cursorforandroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Every word the Extended mode surfaces show, in one place, so the warning can be quoted and tested verbatim. The
 * warning states what the mode does and what it risks and leaves the decision where it belongs; nothing here argues
 * for turning it on, and the default-mode copy never describes the default as the lesser state.
 */
object ExtendedModeCopy {
    const val SETTING_TITLE = "Extended mode"
    const val SETTING_OFF = "Off. Only the documented Cloud Agents API."
    const val SETTING_ON = "On. Also calls undocumented Cursor endpoints."
    const val INDICATOR = "Extended mode"

    const val DIALOG_TITLE = "Turn on Extended mode?"
    const val DIALOG_INTRO =
        "Extended mode makes this app call Cursor endpoints that are not part of the documented Cloud Agents API — the private endpoints Cursor's own apps use. It adds syncing pinned chats with your Cursor account, your account's profile picture, account-level slash commands, chat renaming, and pull request states for every repository host."
    val DIALOG_POINTS: List<String> = listOf(
        "Using these endpoints is against Cursor's Terms of Service (Section 1.5).",
        "Cursor staff have said that accounts doing this can be restricted or banned.",
        "It can break at any time, without notice, when Cursor changes their service.",
        "This project provides no support for Extended mode: if it stops working, it may stay that way.",
    )
    const val DIALOG_CLOSING = "The choice is yours. Nothing else in the app needs it: with Extended mode off, the app uses only the documented API."
    const val DIALOG_CHECKBOX = "I understand the risks and choose to turn on Extended mode"
    const val DIALOG_CONFIRM = "Turn on"
    const val DIALOG_CANCEL = "Cancel"

    const val NOTICE_TITLE = "Extended mode is off by default"
    const val NOTICE_BODY =
        "This version uses only Cursor's documented Cloud Agents API unless Extended mode is turned on. Syncing pinned chats with your Cursor account, your account's profile picture, account-level slash commands and chat renaming now need Extended mode. Your pinned chats are kept on this device, and pull request states come from GitHub for GitHub-hosted repositories. You can read what Extended mode involves, and turn it on, under Settings."
    const val NOTICE_OPEN_SETTINGS = "Open Settings"
    const val NOTICE_DISMISS = "OK"

    /** The dialog's text as one string, for the tests that pin the wording. */
    val DIALOG_TEXT: String get() = listOf(DIALOG_INTRO, DIALOG_POINTS.joinToString("\n") { "• $it" }, DIALOG_CLOSING).joinToString("\n\n")

    // The transcript engine (see TranscriptEngine): shown under the switch while the mode is on, since it only matters then.
    const val ENGINE_TITLE = "Transcript engine"
    const val ENGINE_STABLE = "Stable"
    const val ENGINE_STABLE_DETAIL = "Reads only Cursor's documented API for transcripts. Never shows the \"Account transcript unavailable\" notice."
    const val ENGINE_BETA = "Beta"
    const val ENGINE_BETA_DETAIL = "Adds the account record read over Cursor's private path (can break when Cursor changes it) and other in-progress transcript features."
    const val ENGINE_FOOTNOTE = "Takes effect the next time a chat is opened."
}

/** Test tags, for the tests that drive the toggle and its dialog. */
object ExtendedModeTags {
    const val TOGGLE = "extended_mode_toggle"
    const val DIALOG = "extended_mode_dialog"
    const val DIALOG_CHECKBOX = "extended_mode_dialog_checkbox"
    const val DIALOG_CONFIRM = "extended_mode_dialog_confirm"
    const val NOTICE = "extended_mode_notice"
    const val ENGINE_STABLE = "transcript_engine_stable"
    const val ENGINE_BETA = "transcript_engine_beta"
}

/**
 * The Extended mode section: the toggle, and under it — only while the mode is on, since it matters only then — the
 * transcript engine ([TranscriptEngineRows]). Turning the mode on for the first time opens the acknowledgment
 * dialog, which is the only way the setting can come on; once acknowledged it flips like any other switch, and
 * turning it off is immediate. Everything the mode adds follows the switch — the pins sync with the account exactly
 * while it is on — so there is no option here that could do nothing.
 *
 * [enabled] is the screen's reading of the mode, collected once by the screen and shared with everything on it that
 * shows the mode (the debug sheet's API row), so the two can never disagree for a frame.
 */
@Composable
fun ExtendedModeRows(graph: AppGraph, enabled: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    // On the main dispatcher, as the screen's reading of the mode is (see SettingsScreen): written from the store's IO
    // thread, a first value can be lost to the recomposer's bookkeeping under the test harness's unconfined dispatcher.
    val acknowledgedAt by graph.extendedMode.acknowledgedAt.collectAsStateWithLifecycle(initialValue = null, context = Dispatchers.Main.immediate)
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    fun setEnabled(on: Boolean) {
        when {
            !on -> scope.launch { graph.extendedMode.disable() }
            acknowledgedAt != null -> scope.launch { graph.extendedMode.enable() }
            else -> dialogOpen = true
        }
    }

    Row(
        Modifier.fillMaxWidth().pressable({ setEnabled(!enabled) }, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ExtendedModeCopy.SETTING_TITLE, style = type.base, color = colors.textPrimary)
            Text(if (enabled) ExtendedModeCopy.SETTING_ON else ExtendedModeCopy.SETTING_OFF, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = enabled, onCheckedChange = ::setEnabled, modifier = Modifier.semantics { testTag = ExtendedModeTags.TOGGLE })
    }
    if (enabled) {
        HairlineDivider(Modifier.padding(horizontal = 14.dp))
        TranscriptEngineRows(graph)
    }

    if (dialogOpen) {
        ExtendedModeAcknowledgmentDialog(
            onCancel = { dialogOpen = false },
            onConfirm = {
                dialogOpen = false
                scope.launch {
                    if (graph.extendedMode.acknowledge()) graph.extendedMode.enable()
                }
            },
        )
    }
}

/**
 * The transcript engine (see [TranscriptEngine]): Stable or Beta, one chosen, in plain words about what each reads
 * and risks. Written the moment it is tapped; the footnote says when it takes effect. Under the Extended mode switch
 * because the record it governs is a private surface: with the mode off there is nothing for it to choose.
 */
@Composable
fun TranscriptEngineRows(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val engine by graph.extendedMode.engine.collectAsStateWithLifecycle(initialValue = TranscriptEngine.DEFAULT, context = Dispatchers.Main.immediate)
    Text(
        ExtendedModeCopy.ENGINE_TITLE,
        style = type.rowMedium, color = colors.textTertiary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
    )
    TranscriptEngine.entries.forEach { choice ->
        val (title, detail, tag) = when (choice) {
            TranscriptEngine.STABLE -> Triple(ExtendedModeCopy.ENGINE_STABLE, ExtendedModeCopy.ENGINE_STABLE_DETAIL, ExtendedModeTags.ENGINE_STABLE)
            TranscriptEngine.BETA -> Triple(ExtendedModeCopy.ENGINE_BETA, ExtendedModeCopy.ENGINE_BETA_DETAIL, ExtendedModeTags.ENGINE_BETA)
        }
        val chosen = engine == choice
        Row(
            Modifier.fillMaxWidth()
                .pressable({ scope.launch { graph.extendedMode.setEngine(choice) } }, CursorTheme.shapes.lg)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { testTag = tag; selected = chosen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = type.base, color = colors.textPrimary)
                Text(detail, style = type.small, color = colors.textTertiary)
            }
            Spacer(Modifier.width(12.dp))
            if (chosen) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            else Spacer(Modifier.size(16.dp))
        }
    }
    Text(ExtendedModeCopy.ENGINE_FOOTNOTE, style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp))
}

/**
 * What turning Extended mode on involves, stated once and in full. Not dismissable by a tap outside or the back
 * gesture: it closes on Cancel, or on Turn on once the checkbox says the text has been read. Shown the first time
 * only; the acknowledgment is kept with its time.
 */
@Composable
fun ExtendedModeAcknowledgmentDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var understood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        modifier = Modifier.semantics { testTag = ExtendedModeTags.DIALOG },
        containerColor = colors.elevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        icon = { Icon(CursorIcons.Warning, null, tint = colors.orange, modifier = Modifier.size(22.dp)) },
        title = { Text(ExtendedModeCopy.DIALOG_TITLE, style = type.sectionTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(ExtendedModeCopy.DIALOG_INTRO, style = type.base, color = colors.textSecondary)
                Spacer(Modifier.height(10.dp))
                ExtendedModeCopy.DIALOG_POINTS.forEach { point ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("•", style = type.base, color = colors.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(point, style = type.base, color = colors.textPrimary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(ExtendedModeCopy.DIALOG_CLOSING, style = type.base, color = colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().pressable({ understood = !understood }, CursorTheme.shapes.base).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = understood,
                        onCheckedChange = { understood = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.textTertiary),
                        modifier = Modifier.semantics { testTag = ExtendedModeTags.DIALOG_CHECKBOX },
                    )
                    Text(ExtendedModeCopy.DIALOG_CHECKBOX, style = type.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = understood, modifier = Modifier.semantics { testTag = ExtendedModeTags.DIALOG_CONFIRM }) {
                Text(ExtendedModeCopy.DIALOG_CONFIRM, style = type.baseMedium, color = if (understood) colors.textPrimary else colors.textQuaternary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(ExtendedModeCopy.DIALOG_CANCEL, style = type.baseMedium, color = colors.textSecondary) }
        },
    )
}

/**
 * The one-time notice an upgraded install sees: the features that were on by default until this version are behind
 * Extended mode now. Informational; it can be dismissed, or it can open Settings.
 */
@Composable
fun ExtendedModeUpgradeNotice(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTag = ExtendedModeTags.NOTICE },
        containerColor = colors.elevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        title = { Text(ExtendedModeCopy.NOTICE_TITLE, style = type.sectionTitle) },
        text = { Text(ExtendedModeCopy.NOTICE_BODY, style = type.base, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(ExtendedModeCopy.NOTICE_OPEN_SETTINGS, style = type.baseMedium, color = colors.textPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ExtendedModeCopy.NOTICE_DISMISS, style = type.baseMedium, color = colors.textSecondary) }
        },
    )
}
