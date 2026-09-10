package com.cursorforandroid.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.cursorforandroid.MainActivity
import com.cursorforandroid.R
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.MediaMarkup
import com.cursorforandroid.domain.MediaSegment
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock

/**
 * Builds the notifications that stand in for the iOS Live Activity.
 *
 * Custom RemoteViews are not used: Android 16 Live Updates refuse them, and the system templates are what
 * sports / delivery / media-adjacent live cards are built from.
 *
 *  - One running agent: title, current step, a Stop action, and a chronometer. On Android 16 this is a promoted
 *    `ProgressStyle` Live Update whose bar is a start → work → wrap-up journey (tracker, segment colours, milestone
 *    points) rather than an endless indeterminate spinner. Older releases get the same progress as a determinate bar.
 *  - Several running agents: `InboxStyle` — one row per tracked agent (`Title — step`), stopping first, then the
 *    longest-running. The headline counts every running agent; overflow is a summary (`+N more`), not another bullet.
 *    Still a Live Update on Android 16 (`InboxStyle` is an allowed promoted style).
 *  - A finished agent: a dismissible card with "Finished", the title, "+80 −230 · 3 Files" (or the duration when
 *    no tool reported line counts), the final reply, and Review / View PR actions.
 */
object LiveNotificationRenderer {
    /** Id of the single ongoing notification (also the foreground service notification). */
    const val LIVE_ID = 0x4C495645

    private const val ACCENT = 0xFF81A1C1.toInt()
    private const val GREEN = 0xFF3FA266.toInt()
    private const val RED = 0xFFE34671.toInt()
    private const val GRAY = 0xFF9A9A9A.toInt()
    private const val ORANGE = 0xFFF1B467.toInt()
    private const val GIT_ADDED = 0xFF70B489.toInt()
    private const val GIT_REMOVED = 0xFFFC6B83.toInt()
    private const val ROSTER_TITLE_MAX = 36
    private const val ROSTER_STEP_MAX = 28
    private const val SUMMARY_MAX = 320

    /** Journey bar is 100 units: setup 20, work 60, wrap-up 20. */
    internal const val JOURNEY_MAX = 100
    internal const val JOURNEY_SETUP = 20
    internal const val JOURNEY_WORK = 60
    internal const val JOURNEY_WRAP = 20

    fun finishedId(agentId: String): Int = 0x46000000 or (agentId.hashCode() and 0x00FFFFFF)

    /** Shown for the instant between the service starting and the first reconciled state. */
    fun connecting(context: Context): Notification {
        val builder = liveBuilder(context)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_connecting))
            .setContentIntent(openApp(context))
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(indeterminateStyle())
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun live(context: Context, state: LiveActivityState): Notification {
        val running = state.running
        if (running.isEmpty()) return connecting(context)
        // Chosen by how many agents run, not how many are followed: a second agent beyond the tracked one still counts.
        return if (state.totalRunning == 1) single(context, running.first()) else condensed(context, state)
    }

    private fun single(context: Context, run: TrackedRun): Notification {
        val builder = liveBuilder(context)
            .setContentTitle(run.title)
            .setContentText(stepText(context, run))
            .setSubText(phaseLabel(context, run))
            .setWhen(run.startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openAgent(context, run.agentId))
        if (run.phase != LivePhase.Stopping) {
            builder.addAction(0, context.getString(R.string.notif_action_stop), stopRun(context, run))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(journeyStyle(context, run))
        } else {
            builder.setProgress(JOURNEY_MAX, journeyProgress(run), false)
        }
        return builder.build()
    }

    private fun condensed(context: Context, state: LiveActivityState): Notification {
        // Stopping first (a cancel is in flight), then the longest-running — the same order the monitor already
        // prefers, made explicit so a test-constructed state still reads as a roster rather than insertion order.
        val roster = sortedForRoster(state.running)
        val count = state.totalRunning
        val lines = roster.map { rosterLine(context, it) }
        val more = moreSummary(context, state.untrackedCount)
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.resources.getQuantityString(R.plurals.notif_agents_running, count, count))
        lines.forEach { inbox.addLine(it) }
        more?.let { inbox.setSummaryText(it) }
        return liveBuilder(context)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_agents_running, count, count))
            .setContentText(lines.firstOrNull() ?: more)
            .setStyle(inbox)
            .setWhen(roster.minOfOrNull { it.startedAtMillis } ?: 0L)
            .setShowWhen(false)
            // The chip shows either the chronometer or this text; with several agents the count is the useful one.
            .setShortCriticalText(context.resources.getQuantityString(R.plurals.notif_chip_agents, count, count))
            .setNumber(count)
            .setContentIntent(openApp(context))
            .build()
    }

    fun finished(context: Context, run: TrackedRun): Notification {
        val stats = styledStats(run)
        val summary = run.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { firstParagraph(it) }?.takeIf { it.isNotEmpty() }
        val firstLine: CharSequence? = stats ?: summary?.lineSequence()?.firstOrNull()
        val builder = NotificationCompat.Builder(context, LiveNotifications.CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(statusColor(run.status))
            .setContentTitle(run.title)
            .setSubText(statusLabel(context, run.status))
            .setWhen(run.finishedAtMillis ?: AppClock.now())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAgent(context, run.agentId))
            .addAction(0, context.getString(R.string.notif_action_review), openAgent(context, run.agentId))
        firstLine?.let { builder.setContentText(it) }
        val big = buildList<CharSequence> {
            stats?.let { add(it) }
            summary?.let { add(it) }
        }
        if (big.isNotEmpty()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(join(big)))
        run.prUrl?.let { url ->
            builder.addAction(0, context.getString(R.string.notif_action_view_pr), openUrl(context, url, run.agentId))
        }
        return builder.build()
    }

    // ---- live-update styles -------------------------------------------------------------------------------

    /**
     * Start → work → wrap-up bar for one run. [setStyledByProgress] leaves the unused tail muted, the cube sits at
     * the current step, and the points mark leaving setup and entering wrap-up — the same anatomy sports and
     * delivery Live Updates use.
     */
    internal fun journeyStyle(context: Context, run: TrackedRun): NotificationCompat.ProgressStyle =
        NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(journeyProgress(run))
            .setProgressStartIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_agent))
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.widget_working))
            .setProgressEndIcon(IconCompat.createWithResource(context, R.drawable.widget_git_pull_request))
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(JOURNEY_SETUP).setColor(GRAY),
                    NotificationCompat.ProgressStyle.Segment(JOURNEY_WORK).setColor(ACCENT),
                    NotificationCompat.ProgressStyle.Segment(JOURNEY_WRAP).setColor(if (run.phase == LivePhase.Stopping) ORANGE else GREEN),
                ),
            )
            .setProgressPoints(
                listOf(
                    NotificationCompat.ProgressStyle.Point(JOURNEY_SETUP).setColor(ACCENT),
                    NotificationCompat.ProgressStyle.Point(JOURNEY_SETUP + JOURNEY_WORK).setColor(GREEN),
                ),
            )

    private fun indeterminateStyle(): NotificationCompat.ProgressStyle =
        NotificationCompat.ProgressStyle()
            .setProgressIndeterminate(true)
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(JOURNEY_MAX).setColor(ACCENT)))

    /**
     * 0…[JOURNEY_MAX] along the start → work → wrap-up bar. Phases pin the ends; the current tool verb walks the
     * middle so the tracker actually moves instead of spinning in place.
     */
    internal fun journeyProgress(run: TrackedRun): Int = when (run.phase) {
        LivePhase.Starting -> 10
        LivePhase.Stopping -> 92
        LivePhase.Finished -> JOURNEY_MAX
        LivePhase.Running -> activityProgress(run.digest.activity)
    }

    internal fun activityProgress(activity: RunDigest.Activity): Int {
        val verb = activity.verb.lowercase()
        val hasDetail = !activity.detail.isNullOrBlank()
        return when {
            verb.startsWith("start") -> 14
            verb.startsWith("think") -> 28
            verb.startsWith("delegat") -> 40
            verb.startsWith("finish") -> 84
            verb.startsWith("writ") -> if (hasDetail) 62 else 74
            verb.startsWith("edit") || verb.startsWith("creat") || verb.startsWith("delet") || verb.startsWith("appl") -> 62
            verb.startsWith("run") -> 55
            verb == "working" -> 50
            else -> 50
        }
    }

    /** Stopping first, then longest-running. Equal keys keep the incoming order. */
    internal fun sortedForRoster(runs: List<TrackedRun>): List<TrackedRun> =
        runs.sortedWith(compareBy<TrackedRun> { it.phase != LivePhase.Stopping }.thenBy { it.startedAtMillis })

    // ---- pieces -------------------------------------------------------------------------------------------

    private fun liveBuilder(context: Context) = NotificationCompat.Builder(context, LiveNotifications.CHANNEL_LIVE)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setColor(ACCENT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setRequestPromotedOngoing(true)

    private fun stepText(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting) + "\u2026"
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping) + "\u2026"
        else -> run.digest.activity.let { if (it.detail.isNullOrBlank()) it.verb + "\u2026" else it.label }
    }

    /** `Title — Editing Composer.kt` with the title weighted and the step muted. */
    internal fun rosterLine(context: Context, run: TrackedRun): CharSequence {
        val title = ellipsize(run.title, ROSTER_TITLE_MAX)
        val step = ellipsize(rosterStep(context, run), ROSTER_STEP_MAX)
        val line = SpannableStringBuilder()
        line.append(title)
        line.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.append(" \u2014 ")
        val stepAt = line.length
        line.append(step)
        line.setSpan(ForegroundColorSpan(GRAY), stepAt, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return line
    }

    private fun rosterStep(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting)
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
        else -> run.digest.activity.let { if (it.detail.isNullOrBlank()) it.verb else it.label }
    }

    /** Summary footer for running agents beyond the tracking cap; null when every running agent has a row. */
    internal fun moreSummary(context: Context, untracked: Int): String? =
        if (untracked <= 0) null else context.resources.getQuantityString(R.plurals.notif_more_agents, untracked, untracked)

    private fun phaseLabel(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting)
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
        LivePhase.Running -> context.getString(R.string.notif_status_running)
        LivePhase.Finished -> statusLabel(context, run.status)
    }

    fun statusLabel(context: Context, status: RunStatus): String = when (status) {
        RunStatus.FINISHED -> context.getString(R.string.notif_status_finished)
        RunStatus.ERROR -> context.getString(R.string.notif_status_failed)
        RunStatus.CANCELLED -> context.getString(R.string.notif_status_cancelled)
        RunStatus.EXPIRED -> context.getString(R.string.notif_status_expired)
        RunStatus.CREATING -> context.getString(R.string.notif_status_starting)
        RunStatus.RUNNING, RunStatus.UNKNOWN -> context.getString(R.string.notif_status_running)
    }

    private fun statusColor(status: RunStatus): Int = when (status) {
        RunStatus.FINISHED -> GREEN
        RunStatus.ERROR -> RED
        RunStatus.CANCELLED, RunStatus.EXPIRED -> GRAY
        else -> ACCENT
    }

    /** "+80 −230 · 3 Files" with the counts tinted like the app's git decorations; plain text otherwise. */
    private fun styledStats(run: TrackedRun): CharSequence? {
        val line = run.statsLine() ?: return null
        if (!run.digest.hasLineStats) return line
        val added = "+${run.digest.additions ?: 0}"
        val removed = "\u2212${run.digest.deletions ?: 0}"
        val builder = SpannableStringBuilder(line)
        val addedAt = line.indexOf(added)
        if (addedAt >= 0) builder.setSpan(ForegroundColorSpan(GIT_ADDED), addedAt, addedAt + added.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val removedAt = line.indexOf(removed)
        if (removedAt >= 0) builder.setSpan(ForegroundColorSpan(GIT_REMOVED), removedAt, removedAt + removed.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    private fun firstParagraph(text: String): String {
        // Replies often open with a screenshot or recording; a notification wants the first paragraph of words, and
        // only falls back to an image's alt text when there is nothing else.
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val paragraph = paragraphs.firstOrNull { p -> MediaMarkup.split(p).any { it is MediaSegment.Text } }?.let { MediaMarkup.stripped(it) }
            ?: paragraphs.asSequence().map { MediaMarkup.stripped(it) }.firstOrNull { it.isNotBlank() }
            ?: return ""
        val plain = paragraph.replace(Regex("[*_`#>]+"), "").trim()
        return if (plain.length > SUMMARY_MAX) plain.take(SUMMARY_MAX - 1).trimEnd() + "\u2026" else plain
    }

    private fun join(parts: List<CharSequence>): CharSequence {
        val builder = SpannableStringBuilder()
        parts.forEachIndexed { index, part ->
            if (index > 0) builder.append("\n")
            builder.append(part)
        }
        return builder
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "\u2026"

    // ---- intents ------------------------------------------------------------------------------------------

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun requestCode(kind: String, key: String): Int = (kind + key).hashCode()

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode("app", ""), intent, flags())
    }

    /** Reuses the app's `https://cursor.com/agents/<id>` deep link so the conversation opens directly. */
    private fun openAgent(context: Context, agentId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, CursorEndpoints.webUrl(agentId).toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode("agent", agentId), intent, flags())
    }

    private fun openUrl(context: Context, url: String, key: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode("url", key), intent, flags())
    }

    private fun stopRun(context: Context, run: TrackedRun): PendingIntent =
        PendingIntent.getService(context, requestCode("stop", run.agentId), LiveNotificationService.stopRunIntent(context, run.agentId, run.runId), flags())
}
