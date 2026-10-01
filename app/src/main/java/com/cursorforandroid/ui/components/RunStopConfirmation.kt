package com.cursorforandroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A tap that would end or hold a running agent's turn, in the words its confirmation asks with. [Stop] is the
 * documented cancel (the composer's Stop, the chat's menu, the panel's controls, a Project primary's menu); [Pause]
 * is Extended mode's hold (the panel, a primary's menu). A queued message's up arrow is neither: it steers into the
 * turn under way, which carries on.
 */
enum class RunInterruption(val title: String, val detail: String, val confirm: String) {
    Stop("Stop the agent?", "The run ends where it is. Send a follow-up to carry on.", "Stop"),
    Pause("Pause the agent?", "It holds where it is until you resume it.", "Pause"),
}

/** The words the confirmation shows, shared with the tests. */
object RunStopCopy {
    const val KEEP_RUNNING = "Keep running"
}

object RunStopTags {
    const val DIALOG = "run_stop_dialog"
    const val CONFIRM = "run_stop_confirm"
    const val KEEP_RUNNING = "run_stop_keep_running"
}

/**
 * The question before a tap interrupts a run, where the taps are. [ask] holds the action for [RunStopDialog]: run on
 * the dialog's Stop (or Pause), dropped on Keep running, a tap outside or the back gesture. The composer's Stop rides
 * the keyboard's edge, where a palm finds it, and a run stopped by accident costs a re-prompt — so the question is
 * always asked. The interruption is felt as a [Haptic.Confirm] when it goes through.
 */
@Stable
class RunStopConfirmation internal constructor(private val haptics: Haptics? = null) {
    internal class Pending(val kind: RunInterruption, val subject: String, val action: () -> Unit)

    internal var pending by mutableStateOf<Pending?>(null)
        private set

    /** Asks before [action]. [subject] is the chat whose run it interrupts (see [dismissFor]). */
    fun ask(kind: RunInterruption, subject: String, action: () -> Unit) {
        pending = Pending(kind, subject, action)
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
        interrupt(held.action)
    }

    private fun interrupt(action: () -> Unit) {
        haptics?.perform(Haptic.Confirm)
        action()
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
fun rememberRunStopConfirmation(): RunStopConfirmation {
    val haptics = rememberHaptics()
    return remember(haptics) { RunStopConfirmation(haptics) }
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
