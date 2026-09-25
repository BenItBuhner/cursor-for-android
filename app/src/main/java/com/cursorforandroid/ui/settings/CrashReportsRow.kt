package com.cursorforandroid.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.cursorforandroid.AppGraph
import com.cursorforandroid.crash.CrashLog
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.shareCrashReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings' debug sheet: every crash report kept on the device (see [CrashLog]) — the app's own, and the deaths
 * Android recorded (low memory, ANR, native) — through the share sheet, whether or not the card was closed.
 */
@Composable
fun CrashReportsRow(graph: AppGraph, share: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val send = share ?: { text -> shareCrashReports(context, text) }
    SettingsRow(
        title = CrashReportsCopy.TITLE,
        description = CrashReportsCopy.SUBTITLE,
        modifier = Modifier.testTag(CrashReportsCopy.TAG),
        onClick = { scope.launch { send(withContext(Dispatchers.IO) { graph.crashLog.export(CrashLog.SHARE_MAX_CHARS) }) } },
        trailing = { RowGlyph(CursorIcons.ExternalLink) },
    )
}

object CrashReportsCopy {
    const val TITLE = "Share crash reports"
    const val SUBTITLE = "How the app last crashed or was stopped, kept on this device."
    const val TAG = "crash-reports"
}
