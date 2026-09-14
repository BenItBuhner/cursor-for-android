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
import androidx.core.content.FileProvider
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings › Advanced: exports the redacted account of where every chat was placed — which are Projects, which
 * their workers, side chats and subagents, and which signal placed each (see `ProjectDiagnostics`) — through the
 * share sheet in one tap — as text and as a `.txt` file through the app's FileProvider — so a Project's chat that
 * still shows among the account's own can be sent as a dump rather than described. Ids are shortened to their
 * tails; no names, prompts or tokens leave the device. [share] is what is done with the text; the default opens the
 * system's share sheet.
 */
@Composable
fun ProjectDiagnosticsRow(graph: AppGraph, share: ((String) -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val send = share ?: { report ->
        // The report goes as a file as well as text: a heavy account's report runs to hundreds of lines, more than
        // some receivers take as text, and a `.txt` attaches to mail, Slack or Drive as it is.
        val file = runCatching {
            File(context.cacheDir, "diagnostics").apply { mkdirs() }.resolve("cursor-project-diagnostics-${System.currentTimeMillis()}.txt").also { it.writeText(report) }
        }.getOrNull()
        val uri = file?.let { runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            setType("text/plain")
            putExtra(Intent.EXTRA_SUBJECT, "Cursor for Android · Project diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share Project diagnostics").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
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
                        send(graph.projectDiagnosticsReport())
                    } finally {
                        busy = false
                    }
                }
            }, CursorTheme.shapes.lg)
            .testTag(ProjectDiagnosticsCopy.TAG)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ProjectDiagnosticsCopy.TITLE, style = type.base, color = colors.textPrimary)
            Text(ProjectDiagnosticsCopy.SUBTITLE, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
    }
}

object ProjectDiagnosticsCopy {
    const val TITLE = "Export Project diagnostics"
    const val SUBTITLE = "Which chats are Projects, workers and side chats, and what placed each. Ids shortened; no names or text. Send it if a Project's chat still shows among your chats."
    const val TAG = "project-diagnostics"
}
