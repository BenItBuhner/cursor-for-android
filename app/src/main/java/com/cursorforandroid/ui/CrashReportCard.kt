package com.cursorforandroid.ui

import android.content.Context
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.crash.CrashLog
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.ui.conversation.LoadNoticeCard
import com.cursorforandroid.ui.conversation.NoticeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Offered once after the app died: what it died of, when, and one tap to share the report (see [CrashLog]) — or, in
 * Extended mode, to send it straight into the Cursor for Android Project's inbox. Closing it keeps the report for
 * Settings › Debug; the card does not come back for it.
 */
@Composable
fun CrashReportCard(graph: AppGraph, canSend: Boolean, modifier: Modifier = Modifier) {
    val pending by graph.crashLog.pending.collectAsStateWithLifecycle()
    val report = pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
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
        if (canSend) {
            NoticeAction(CrashReportCopy.SEND, {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val path = graph.sendCrashReportsToProject()
                            Toast.makeText(context, CrashReportCopy.SENT + path, Toast.LENGTH_LONG).show()
                            graph.crashLog.dismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Toast.makeText(context, CrashReportCopy.NOT_SENT + t.userMessage(), Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                }
            }, Modifier.testTag(CrashReportCopy.SEND_TAG))
        }
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
    const val SEND = "Send to Project"
    const val SENT = "Sent to the Project: "
    const val NOT_SENT = "Not sent: "
    const val TAG = "crash-report-card"
    const val TITLE_TAG = "crash-report-title"
    const val SHARE_TAG = "crash-report-share"
    const val SEND_TAG = "crash-report-send"
}
