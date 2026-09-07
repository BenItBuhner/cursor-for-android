package com.cursorforandroid.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.cursorforandroid.MainActivity
import com.cursorforandroid.R
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.util.AppClock

/**
 * Builds the notifications that stand in for the iOS Live Activity.
 *
 *  - One running agent: a card with the status label, the agent title, the current step and a Stop action; the
 *    header chronometer counts the run up. On Android 16 it is a promoted `ProgressStyle` Live Update.
 *  - Several running agents: the same card condensed to one line per agent, `BigTextStyle` so it still qualifies
 *    for promotion. Only the tracked runs (up to eight) get a line; the headline counts every running agent and a
 *    closing "+N more" line stands in for the rest.
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
    private const val GIT_ADDED = 0xFF70B489.toInt()
    private const val GIT_REMOVED = 0xFFFC6B83.toInt()
    private const val CONDENSED_TITLE_MAX = 34
    private const val SUMMARY_MAX = 320

    fun finishedId(agentId: String): Int = 0x46000000 or (agentId.hashCode() and 0x00FFFFFF)

    /** Shown for the instant between the service starting and the first reconciled state. */
    fun connecting(context: Context): Notification = liveBuilder(context)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notif_connecting))
        .setContentIntent(openApp(context))
        .setProgress(0, 0, true)
        .build()

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
            builder.setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun condensed(context: Context, state: LiveActivityState): Notification {
        val running = state.running
        // The count is every running agent; the lines only cover the tracked ones, so the rest are summed up below.
        val count = state.totalRunning
        val lines = running.map { condensedLine(context, it) } + listOfNotNull(moreLine(context, state.untrackedCount))
        return liveBuilder(context)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_agents_running, count, count))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setWhen(running.minOf { it.startedAtMillis })
            .setShowWhen(false)
            // The chip shows either the chronometer or this text; with several agents the count is the useful one.
            .setShortCriticalText(context.resources.getQuantityString(R.plurals.notif_chip_agents, count, count))
            .setNumber(count)
            .setContentIntent(openApp(context))
            .build()
    }

    fun finished(context: Context, run: TrackedRun): Notification {
        val stats = styledStats(run)
        val summary = run.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { firstParagraph(it) }
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

    private fun condensedLine(context: Context, run: TrackedRun): String {
        val step = when (run.phase) {
            LivePhase.Starting -> context.getString(R.string.notif_status_starting)
            LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
            else -> run.digest.activity.verb
        }
        return "\u2022 ${ellipsize(run.title, CONDENSED_TITLE_MAX)} \u00B7 $step"
    }

    /** "• +2 more" for the running agents beyond the tracking cap; null when every running agent has a line. */
    private fun moreLine(context: Context, untracked: Int): String? =
        if (untracked <= 0) null else "\u2022 " + context.resources.getQuantityString(R.plurals.notif_more_agents, untracked, untracked)

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
        val paragraph = text.split(Regex("\\n\\s*\\n")).firstOrNull { it.isNotBlank() }?.trim() ?: text
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
