package com.cursorforandroid.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.crash.CrashLog
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.ui.conversation.LoadNoticeCard
import com.cursorforandroid.ui.conversation.NoticeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Offered once after the app died: what it died of, when, and one tap to share the report (see [CrashLog]). Closing
 * it keeps the report for Settings › Debug; the card does not come back for it.
 */
@Composable
fun CrashReportCard(graph: AppGraph, modifier: Modifier = Modifier) {
    val pending by graph.crashLog.pending.collectAsStateWithLifecycle()
    val report = pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LoadNoticeCard(
        title = CrashReportCopy.TITLE,
        detail = "${report.headline} · ${WHEN.format(Instant.ofEpochMilli(report.atMillis))}",
        tone = NoticeTone.Warning,
        docked = false,
        onDismiss = { graph.crashLog.dismiss() },
        titleTag = CrashReportCopy.TITLE_TAG,
        modifier = modifier.testTag(CrashReportCopy.TAG),
    ) {
        NoticeAction(CrashReportCopy.SHARE, {
            scope.launch {
                val text = withContext(Dispatchers.IO) { graph.crashLog.export(CrashLog.SHARE_MAX_CHARS) }
                shareCrashReports(context, text)
            }
        }, Modifier.testTag(CrashReportCopy.SHARE_TAG))
    }
}

/** The share sheet with [text], the kept crash reports; says so when nothing can receive it. */
fun shareCrashReports(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        setType("text/plain")
        putExtra(Intent.EXTRA_SUBJECT, "Cursor for Android · Crash report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "Nothing on this device can receive the report.", Toast.LENGTH_SHORT).show() }
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

object CrashReportCopy {
    const val TITLE = "The app crashed last time"
    const val SHARE = "Share report"
    const val TAG = "crash-report-card"
    const val TITLE_TAG = "crash-report-title"
    const val SHARE_TAG = "crash-report-share"
}
