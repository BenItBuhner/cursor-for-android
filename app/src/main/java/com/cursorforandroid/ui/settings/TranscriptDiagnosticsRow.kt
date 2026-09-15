package com.cursorforandroid.ui.settings

import android.content.Intent
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
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * Settings' debug sheet (a long press on the version row): exports the redacted account of the last opened chat's transcript as this build reads it (see
 * `TranscriptDiagnostics`) — the kinds of its items, the tool names with their argument key names, the payload read
 * off each call, and the decision that reads the chat as a Project coordinator's — through the share sheet, so a
 * coordinator's chat that still shows its notes as messages can be sent as a dump rather than described. No message,
 * prompt or argument text leaves the device. [share] is what is done with the text; the default opens the share sheet.
 */
@Composable
fun TranscriptDiagnosticsRow(graph: AppGraph, share: ((String) -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val send = share ?: { report ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            setType("text/plain")
            putExtra(Intent.EXTRA_SUBJECT, "Cursor for Android · Transcript diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share transcript diagnostics")) }
            .onFailure { Toast.makeText(context, "Nothing on this device can receive the report.", Toast.LENGTH_SHORT).show() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({
                if (busy) return@pressable
                busy = true
                scope.launch {
                    try {
                        send(graph.transcriptDiagnosticsReport())
                    } finally {
                        busy = false
                    }
                }
            }, CursorTheme.shapes.lg)
            .testTag(TranscriptDiagnosticsCopy.TAG)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(TranscriptDiagnosticsCopy.TITLE, style = type.base, color = colors.textPrimary)
            Text(TranscriptDiagnosticsCopy.SUBTITLE, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
    }
}

object TranscriptDiagnosticsCopy {
    const val TITLE = "Export transcript diagnostics"
    const val SUBTITLE = "The last opened chat's items and tool calls — names, kinds and argument keys, no text — and whether it reads as a Project coordinator's. Send it if a coordinator's updates still show wrong."
    const val TAG = "transcript-diagnostics"
}
