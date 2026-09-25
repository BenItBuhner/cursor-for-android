package com.cursorforandroid.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where the app's own diagnostics go when sent from Settings › Debug: the context store of the Cursor for Android
 * Project — the Project whose coordinator and workers maintain this app — under `inbox/diagnostics/`, one file per
 * send, named by the moment it was written. The workers read the store as the Project's shared context; the two
 * exports (see [ProjectDiagnostics] and [TranscriptDiagnostics]) are already redacted, so the file is what Settings
 * would have handed to the share sheet, with nothing added but a header.
 */
object DiagnosticsInbox {
    /** The Project coordinator whose store the inbox is in. */
    const val PROJECT_ID = "bc-bae107cb-2562-40b2-b814-4f8eca874668"

    const val DIRECTORY = "inbox/diagnostics"

    const val DEMO_HAS_NO_ACCOUNT = "The demo has no account to send diagnostics from."

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** `inbox/diagnostics/20260918T183012Z.txt`: sortable, unique to the second, safe as a store path. */
    fun path(nowMillis: Long): String = "$DIRECTORY/${STAMP.format(Instant.ofEpochMilli(nowMillis))}.txt"

    /** `inbox/diagnostics/20260925T183012Z-crash.txt`: the crash card's send, beside the exports. */
    fun crashPath(nowMillis: Long): String = "$DIRECTORY/${STAMP.format(Instant.ofEpochMilli(nowMillis))}-crash.txt"

    /** The file's text: a header, then the two exports as Settings shares them, and the crash reports kept, when there are any. */
    fun compose(appVersion: String, nowMillis: Long, projectReport: String, transcriptReport: String, crashReports: String? = null): String = buildString {
        appendLine("Cursor for Android $appVersion · diagnostics sent from Settings › Debug · ${Instant.ofEpochMilli(nowMillis)}")
        appendLine("Two exports follow, redacted as the share sheet gets them: ids are tails, no names, prompts, messages or argument text.")
        appendLine()
        appendLine("########## Project diagnostics ##########")
        appendLine(projectReport.trimEnd())
        appendLine()
        appendLine("########## Transcript diagnostics ##########")
        appendLine(transcriptReport.trimEnd())
        crashReports?.let {
            appendLine()
            appendLine("########## Crash reports ##########")
            appendLine(it.trimEnd())
        }
    }

    /** The crash card's file: a header, the crash reports kept, then the Project diagnostics for the account's shape. */
    fun composeCrash(appVersion: String, nowMillis: Long, crashReports: String, projectReport: String): String = buildString {
        appendLine("Cursor for Android $appVersion · crash reports sent from the crash card · ${Instant.ofEpochMilli(nowMillis)}")
        appendLine()
        appendLine("########## Crash reports ##########")
        appendLine(crashReports.trimEnd())
        appendLine()
        appendLine("########## Project diagnostics ##########")
        appendLine(projectReport.trimEnd())
    }
}
