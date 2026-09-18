package com.cursorforandroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings' debug sheet: one tap writes the two redacted exports above into the Cursor for Android Project's context
 * store (`inbox/diagnostics/<timestamp>.txt`, see `DiagnosticsInbox`) through the account's store writes, so the
 * Project's workers read them from the store rather than from a paste. Extended mode; in default mode, or with no
 * store listed, the toast says so. [send] is what is done on the tap; the default is the graph's own write, and the
 * toast carries the path written or the reason it was not.
 */
@Composable
fun SendDiagnosticsRow(graph: AppGraph, send: (suspend () -> String)? = null, toast: ((String) -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val say = toast ?: { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val write = send ?: { graph.sendDiagnosticsToProject() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({
                if (busy) return@pressable
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
            }, CursorTheme.shapes.lg)
            .testTag(SendDiagnosticsCopy.TAG)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(SendDiagnosticsCopy.TITLE, style = type.base, color = colors.textPrimary)
            Text(SendDiagnosticsCopy.SUBTITLE, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
    }
}

object SendDiagnosticsCopy {
    const val TITLE = "Send diagnostics to the Cursor for Android Project"
    const val SUBTITLE = "Writes both exports above, redacted as they are shared, into the Project's context store (inbox/diagnostics) for its workers to read. Extended mode."
    const val SENT = "Sent to the Project: "
    const val FAILED = "Not sent: "
    const val TAG = "send-diagnostics"
}
