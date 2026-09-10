package com.cursorforandroid.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
 * Custom RemoteViews are not used: Android 16 Live Updates refuse them. Every live card is therefore one official
 * template — the same ProgressStyle sports / delivery / navigation use — never a bullet list.
 *
 *  - The headline is the current step (`Editing Composer.kt`), like a score. The agent title (or a fleet ticker)
 *    sits underneath. A generated cube poster is the large icon. The card is colorized so it reads as a live
 *    surface, not a generic status row.
 *  - One running agent: that step, the title, a Stop action, a chronometer, and a single determinate bar. Android 16
 *    promotes it as a ProgressStyle Live Update (tracker walking a start → wrap-up bar toward the PR glyph).
 *  - Several running agents: still one ProgressStyle card. The lead (stopping first, then longest-running) owns the
 *    headline and the bar; the supporting line is `N agents · Thinking · Running`. The chip is the count. Tap opens
 *    the lead. Overflow beyond the tracking cap is `+N more` on that same line.
 *  - A finished agent: a dismissible card with "Finished", the title, "+80 −230 · 3 Files" (or the duration when
 *    no tool reported line counts), the final reply, and Review / View PR actions.
 */
object LiveNotificationRenderer {
    /** Id of the single ongoing notification (also the foreground service notification). */
    const val LIVE_ID = 0x4C495645

    private const val ACCENT = 0xFF81A1C1.toInt()
    /** Deep polar-night wash so a colorized card is a live surface, not a black row, and the frost bar still reads. */
    private const val COLORIZED = 0xFF31475C.toInt()
    private const val POSTER_BG = 0xFF141414.toInt()
    private const val GREEN = 0xFF3FA266.toInt()
    private const val RED = 0xFFE34671.toInt()
    private const val GRAY = 0xFF9A9A9A.toInt()
    private const val ORANGE = 0xFFF1B467.toInt()
    private const val GIT_ADDED = 0xFF70B489.toInt()
    private const val GIT_REMOVED = 0xFFFC6B83.toInt()
    private const val TICKER_SEP = " \u00B7 "
    private const val SUMMARY_MAX = 320
    private const val POSTER_DP = 56

    /** Journey bar is 100 units. Phases pin the ends; the current verb walks the middle. */
    internal const val JOURNEY_MAX = 100

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
        return if (state.totalRunning == 1) single(context, running.first()) else fleet(context, state)
    }

    private fun single(context: Context, run: TrackedRun): Notification {
        val step = headline(context, run)
        val builder = liveBuilder(context)
            .setContentTitle(step)
            .setContentText(run.title)
            .setShortCriticalText(chipVerb(context, run))
            .setWhen(run.startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openAgent(context, run.agentId))
        if (run.phase != LivePhase.Stopping) {
            builder.addAction(0, context.getString(R.string.notif_action_stop), stopRun(context, run))
        }
        applyJourney(builder, context, run)
        return builder.build()
    }

    private fun fleet(context: Context, state: LiveActivityState): Notification {
        // Stopping first (a cancel is in flight), then the longest-running — the same order the monitor already
        // prefers, made explicit so a test-constructed state still picks a stable lead.
        val roster = sortedForRoster(state.running)
        val lead = roster.first()
        val count = state.totalRunning
        val builder = liveBuilder(context)
            .setContentTitle(headline(context, lead))
            .setContentText(fleetSupporting(context, state, lead))
            .setShortCriticalText(count.toString())
            .setNumber(count)
            .setWhen(roster.minOfOrNull { it.startedAtMillis } ?: 0L)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openAgent(context, lead.agentId))
        applyJourney(builder, context, lead)
        return builder.build()
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

    private fun applyJourney(builder: NotificationCompat.Builder, context: Context, run: TrackedRun) {
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(journeyStyle(context, run))
        } else {
            builder.setProgress(JOURNEY_MAX, journeyProgress(run), false)
        }
    }

    /**
     * One frost (or amber, while stopping) bar. [setStyledByProgress] mutes the unused tail; the working glyph walks
     * it; the PR icon is the destination. No setup / wrap-up theatre and no milestone dots — those read as a wizard.
     */
    internal fun journeyStyle(context: Context, run: TrackedRun): NotificationCompat.ProgressStyle =
        NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(journeyProgress(run))
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.widget_working))
            .setProgressEndIcon(IconCompat.createWithResource(context, R.drawable.widget_git_pull_request))
            .setProgressSegments(
                listOf(NotificationCompat.ProgressStyle.Segment(JOURNEY_MAX).setColor(barColor(run))),
            )

    private fun indeterminateStyle(): NotificationCompat.ProgressStyle =
        NotificationCompat.ProgressStyle()
            .setProgressIndeterminate(true)
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(JOURNEY_MAX).setColor(ACCENT)))

    private fun barColor(run: TrackedRun): Int = if (run.phase == LivePhase.Stopping) ORANGE else ACCENT

    /**
     * 0…[JOURNEY_MAX] along the bar. Phases pin the ends; the current tool verb walks the middle so the tracker
     * actually moves instead of spinning in place.
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
        .setLargeIcon(poster(context))
        .setColor(COLORIZED)
        .setColorized(true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setRequestPromotedOngoing(true)

    /** Current step — the score on the card. Bare verbs keep the ellipsis so they still read as in-flight. */
    internal fun headline(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting) + "\u2026"
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping) + "\u2026"
        else -> run.digest.activity.let { if (it.detail.isNullOrBlank()) it.verb + "\u2026" else it.label }
    }

    /** Status-bar chip: the verb only, short enough for the Live Update pill. */
    internal fun chipVerb(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting)
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
        else -> run.digest.activity.verb
    }

    /**
     * `3 agents · Thinking · Running · +2 more` — count, then distinct other verbs, then overflow. The lead's own
     * verb is the title, so it is not repeated here.
     */
    internal fun fleetSupporting(context: Context, state: LiveActivityState, lead: TrackedRun): String {
        val count = state.totalRunning
        val leadVerb = tickerVerb(context, lead)
        val others = sortedForRoster(state.running)
            .asSequence()
            .filter { it.agentId != lead.agentId }
            .map { tickerVerb(context, it) }
            .filter { it != leadVerb }
            .distinct()
            .take(2)
            .toList()
        val more = moreSummary(context, state.untrackedCount)
        return buildList {
            add(context.resources.getQuantityString(R.plurals.notif_chip_agents, count, count))
            addAll(others)
            more?.let { add(it) }
        }.joinToString(TICKER_SEP)
    }

    private fun tickerVerb(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting)
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
        else -> run.digest.activity.verb
    }

    /** Summary fragment for running agents beyond the tracking cap; null when every running agent is on the card. */
    internal fun moreSummary(context: Context, untracked: Int): String? =
        if (untracked <= 0) null else context.resources.getQuantityString(R.plurals.notif_more_agents, untracked, untracked)

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

    /** Dark rounded square + the official cube — the album-art slot on the live card. */
    internal fun poster(context: Context): Bitmap {
        val size = (POSTER_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POSTER_BG }
        val radius = size * 0.22f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_stat_agent)?.mutate()
        if (glyph != null) {
            glyph.setTint(0xFFFFFFFF.toInt())
            val inset = (size * 0.22f).toInt()
            glyph.setBounds(inset, inset, size - inset, size - inset)
            glyph.draw(canvas)
        }
        return bitmap
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
