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
import androidx.core.content.FileProvider
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.components.CursorIcons
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings' debug sheet (a long press on the version row): exports the redacted account of where every chat was placed — which are Projects, which
 * their workers, side chats and subagents, and which signal placed each (see `ProjectDiagnostics`) — through the
 * share sheet in one tap — as text and as a `.txt` file through the app's FileProvider — so a Project's chat that
 * still shows among the account's own can be sent as a dump rather than described. Ids are shortened to their
 * tails; no names, prompts or tokens leave the device. [share] is what is done with the text; the default opens the
 * system's share sheet.
 */
@Composable
fun ProjectDiagnosticsRow(graph: AppGraph, share: ((String) -> Unit)? = null) {
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
    SettingsRow(
        title = ProjectDiagnosticsCopy.TITLE,
        description = ProjectDiagnosticsCopy.SUBTITLE,
        modifier = Modifier.testTag(ProjectDiagnosticsCopy.TAG),
        onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        send(graph.projectDiagnosticsReport())
                    } finally {
                        busy = false
                    }
                }
            }
        },
        trailing = { RowGlyph(CursorIcons.ExternalLink) },
    )
}

/**
 * The refresh that reads everything: the sidebar's window and, behind it, the whole account list for the root
 * registry — a pull stops that scan at the page older than every Project already known, so a Project older than
 * all of them is found by this. Runs in the background; the sidebar's footer says so while it does.
 */
@Composable
fun DeepRefreshRow(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    SettingsRow(
        title = DeepRefreshCopy.TITLE,
        description = DeepRefreshCopy.SUBTITLE,
        modifier = Modifier.testTag(DeepRefreshCopy.TAG),
        onClick = {
            if (!busy) {
                busy = true
                Toast.makeText(context, DeepRefreshCopy.STARTED, Toast.LENGTH_SHORT).show()
                scope.launch {
                    try {
                        graph.agents.refresh(depth = com.cursorforandroid.data.repo.RefreshDepth.Deep)
                    } finally {
                        busy = false
                    }
                }
            }
        },
        trailing = { RowGlyph(CursorIcons.Refresh) },
    )
}

object DeepRefreshCopy {
    const val TITLE = "Deep refresh"
    const val SUBTITLE = "Re-reads the whole account to find Projects."
    const val STARTED = "Deep refresh started"
    const val TAG = "deep-refresh"
}

object ProjectDiagnosticsCopy {
    const val TITLE = "Export Project diagnostics"
    const val SUBTITLE = "How each chat was placed; no names or text."
    const val TAG = "project-diagnostics"
}
