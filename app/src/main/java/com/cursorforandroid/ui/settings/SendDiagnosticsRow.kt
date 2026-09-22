package com.cursorforandroid.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.ui.components.CursorIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings' debug sheet: one tap writes the two redacted exports above into the Cursor for Android Project's context
 * store (`inbox/diagnostics/<timestamp>.txt`, see `DiagnosticsInbox`) through the account's store writes, so the
 * Project's workers read them from the store rather than from a paste. The writes are Extended mode's, so outside
 * the mode the row is not [enabled] and says why; with no store listed, the toast says so. [send] is what is done on
 * the tap; the default is the graph's own write, and the toast carries the path written or the reason it was not.
 */
@Composable
fun SendDiagnosticsRow(graph: AppGraph, enabled: Boolean = true, send: (suspend () -> String)? = null, toast: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val say = toast ?: { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val write = send ?: { graph.sendDiagnosticsToProject() }
    SettingsRow(
        title = SendDiagnosticsCopy.TITLE,
        description = if (enabled) SendDiagnosticsCopy.SUBTITLE else ExtendedModeCopy.NEEDS_MODE,
        modifier = Modifier.testTag(SendDiagnosticsCopy.TAG),
        enabled = enabled,
        onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        say(SendDiagnosticsCopy.SENT + write())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        say(SendDiagnosticsCopy.FAILED + t.userMessage())
                    } finally {
                        busy = false
                    }
                }
            }
        },
        trailing = { RowGlyph(CursorIcons.ExternalLink) },
    )
}

object SendDiagnosticsCopy {
    const val TITLE = "Send to the Cursor for Android Project"
    const val SUBTITLE = "Writes both exports to the Project's Context."
    const val SENT = "Sent to the Project: "
    const val FAILED = "Not sent: "
    const val TAG = "send-diagnostics"
}
