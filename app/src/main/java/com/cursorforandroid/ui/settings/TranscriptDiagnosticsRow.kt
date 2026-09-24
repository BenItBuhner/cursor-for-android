package com.cursorforandroid.ui.settings

import android.content.Intent
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
import com.cursorforandroid.ui.components.CursorIcons
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
    SettingsRow(
        title = TranscriptDiagnosticsCopy.TITLE,
        description = TranscriptDiagnosticsCopy.SUBTITLE,
        modifier = Modifier.testTag(TranscriptDiagnosticsCopy.TAG),
        onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        send(graph.transcriptDiagnosticsReport())
                    } finally {
                        busy = false
                    }
                }
            }
        },
        trailing = { RowGlyph(CursorIcons.ExternalLink) },
    )
}

object TranscriptDiagnosticsCopy {
    const val TITLE = "Export transcript diagnostics"
    const val SUBTITLE = "The last opened chat's structure; no text."
    const val TAG = "transcript-diagnostics"
}
