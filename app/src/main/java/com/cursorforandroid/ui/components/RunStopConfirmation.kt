package com.cursorforandroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A tap that would end or hold a running agent's turn, in the words its confirmation asks with. [Stop] is the
 * documented cancel (the composer's Stop, the chat's menu, the panel's controls, a Project primary's menu); [Pause]
 * is Extended mode's hold (the panel, a primary's menu); [SendNow] is a queued message sent while a turn is under
 * way, which cancels that turn for it (this device's queue and the account's alike).
 */
enum class RunInterruption(val title: String, val detail: String, val confirm: String) {
    Stop("Stop the agent?", "The run ends where it is. Send a follow-up to carry on.", "Stop"),
    Pause("Pause the agent?", "It holds where it is until you resume it.", "Pause"),
    SendNow("Interrupt the agent?", "The turn under way stops, and this message is sent in its place.", "Send now"),
}

/** The words the confirmation and its Settings switch show, shared with the tests. */
object RunStopCopy {
    const val KEEP_RUNNING = "Keep running"
    const val SETTING_TITLE = "Confirm before stopping"
    const val SETTING_DETAIL = "Ask before Stop, Pause or Send now interrupts a run."
}

object RunStopTags {
    const val DIALOG = "run_stop_dialog"
    const val CONFIRM = "run_stop_confirm"
    const val KEEP_RUNNING = "run_stop_keep_running"
}

/**
 * Settings › Confirm before stopping, where the taps are. [ask] runs the action at once with the setting off, and
 * otherwise holds it for [RunStopDialog]: run on the dialog's Stop (or Pause, or Send now), dropped on Keep running, a
 * tap outside or the back gesture. The composer's Stop rides the keyboard's edge, where a palm finds it, and a run
 * stopped by accident costs a re-prompt — so the question is asked unless the user has said not to.
 *
 * The setting is read as the screen collects it; a tap in the moment before its first value has arrived reads the
 * store instead of guessing either way.
 */
@Stable
class RunStopConfirmation internal constructor(
    private val setting: State<Boolean?>,
    private val prefs: PreferencesStore,
    private val scope: CoroutineScope,
) {
    internal class Pending(val kind: RunInterruption, val subject: String, val action: () -> Unit)

    internal var pending by mutableStateOf<Pending?>(null)
        private set

    /** Asks before [action], or runs it now with the setting off. [subject] is the chat whose run it interrupts (see [dismissFor]). */
    fun ask(kind: RunInterruption, subject: String, action: () -> Unit) {
        when (setting.value) {
            true -> pending = Pending(kind, subject, action)
            false -> action()
            null -> scope.launch { if (prefs.confirmStop.first()) pending = Pending(kind, subject, action) else action() }
        }
    }

    /**
     * Withdraws the question about [subject]'s run once that run has ended by itself: there is nothing left to stop,
     * and a Stop answered later would land on whatever turn the chat is on by then.
     */
    fun dismissFor(subject: String) {
        if (pending?.subject == subject) pending = null
    }

    internal fun accept() {
        val held = pending ?: return
        pending = null
        held.action()
    }

    internal fun keepRunning() {
        pending = null
    }
}

/**
 * The confirmation of the screen a control is on — the chat's, for its panel and the Project section inside it. Null
 * where no screen provides one (a section rendered on its own, as in previews): the tap then acts at once.
 */
val LocalRunStopConfirmation = staticCompositionLocalOf<RunStopConfirmation?> { null }

/** [RunStopConfirmation.ask] on the confirmation there is, or [action] at once where there is none. */
fun RunStopConfirmation?.askOrRun(kind: RunInterruption, subject: String, action: () -> Unit) {
    if (this == null) action() else ask(kind, subject, action)
}

@Composable
fun rememberRunStopConfirmation(prefs: PreferencesStore): RunStopConfirmation {
    // On the main dispatcher, as Settings collects its switches: written from the store's IO thread, a first value
    // can be lost to the recomposer's bookkeeping under the test harness's unconfined dispatcher.
    val setting: State<Boolean?> = prefs.confirmStop.collectAsStateWithLifecycle(initialValue = null, context = Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    return remember(setting, prefs, scope) { RunStopConfirmation(setting, prefs, scope) }
}

/** The question [confirmation] holds, while it holds one. */
@Composable
fun RunStopDialog(confirmation: RunStopConfirmation) {
    val held = confirmation.pending ?: return
    RunStopDialog(held.kind, onConfirm = confirmation::accept, onKeepRunning = confirmation::keepRunning)
}

/**
 * "Stop the agent?", in the app's dialog idiom. Keep running is the default: it is the dismiss action, so a tap
 * outside the dialog and the back gesture leave the run going as it does; the action that interrupts is in red.
 */
@Composable
fun RunStopDialog(kind: RunInterruption, onConfirm: () -> Unit, onKeepRunning: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    AlertDialog(
        onDismissRequest = onKeepRunning,
        modifier = Modifier.testTag(RunStopTags.DIALOG),
        containerColor = colors.elevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        title = { Text(kind.title, style = type.sectionTitle) },
        text = { Text(kind.detail, style = type.base, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(RunStopTags.CONFIRM)) {
                Text(kind.confirm, style = type.baseMedium, color = colors.red)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepRunning, modifier = Modifier.testTag(RunStopTags.KEEP_RUNNING)) {
                Text(RunStopCopy.KEEP_RUNNING, style = type.baseMedium, color = colors.textPrimary)
            }
        },
    )
}
