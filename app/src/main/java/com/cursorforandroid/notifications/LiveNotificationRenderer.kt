package com.cursorforandroid.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import com.cursorforandroid.util.TimeFormat

/**
 * Builds the notifications that stand in for the iOS Live Activity.
 *
 * There is exactly one live notification, whatever the number of running agents: Android promotes one Live Update per
 * app, and several ongoing cards splinter across the shade (One UI in particular refuses to stack them). Custom
 * RemoteViews are not used either — Android 16 refuses to promote them — so everything below is an official template
 * dressed with parcelable spans.
 *
 *  - **One running agent:** the conversation card. Title is the agent, the supporting line is the current step
 *    (`Editing Composer.kt`), the header carries a chronometer (and, below Android 16, the verb — that collapsed row
 *    has no room for the step), a determinate bar walks the run, a Stop action cancels it, and a cube tile in the
 *    agent's hue fills the large-icon slot. Android 16 promotes it as a `ProgressStyle` Live Update.
 *  - **Several running agents:** the roster card — one `BigTextStyle` notification (a promotable style; `InboxStyle`
 *    is not). The large-icon slot holds a mosaic of the fleet, one small tile per running agent in its hue; the
 *    body is a row per tracked agent — name in bold, verb and elapsed time muted, and below Android 16 a thin edge
 *    in the agent's hue (16 strips colour spans from promoted text, so there the rows are plain bold-and-muted).
 *    Stopping sorts first, then longest-running; agents beyond the tracking cap are a `+N more` tail row. The chip
 *    is the count. Tap opens the app.
 *  - **A finished agent:** a dismissible card with "Finished", the title, "+80 −230 · 3 Files" (or the duration when
 *    no tool reported line counts), the final reply, and Review / View PR actions.
 *
 * Nothing is colorized: `setColorized` disqualifies a Live Update on Android 16.
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
    /** Ink for the cube on a tile: the theme's `onAccent`. */
    private const val TILE_INK = 0xFF191C22.toInt()
    /** The verb and age beside a roster name. */
    private const val MUTED = 0xFFB0B0B0.toInt()
    /** Cursor Dark's accent hues, dealt to agents by id so rows are told apart at a glance. */
    private val TILE_HUES = intArrayOf(
        0xFF81A1C1.toInt(), // accent
        0xFFB48EAD.toInt(), // purple
        0xFF88C0D0.toInt(), // cyan
        0xFF70B489.toInt(), // git added
        0xFFEBC88D.toInt(), // code function
        0xFFA8CC7C.toInt(), // code string
    )
    /** Roster row: a left-quarter block (U+258E) as the colour edge, then name, then a two-space column gap. */
    private const val ROW_EDGE = "\u258E "
    private const val ROW_GAP = "\u2002\u2002"
    private const val ROW_SEP = " \u00B7 "
    /** Sized so `▎ Hyper-realistic human li…  Stopping · 6m` stays on one line of the body at 14sp. */
    private const val ROW_NAME_MAX = 24
    private const val SUMMARY_MAX = 320
    private const val TILE_DP = 56
    /** Mosaic cells beyond the running agents are this faint, so the grid also reads as a count. */
    private const val MOSAIC_EMPTY = 0x1FFFFFFF
    private const val MOSAIC_BG = 0xFF1E1E1E.toInt()

    /** Journey bar is 100 units. Phases pin the ends; the current verb walks the middle. */
    internal const val JOURNEY_MAX = 100

    fun finishedId(agentId: String): Int = 0x46000000 or (agentId.hashCode() and 0x00FFFFFF)

    /** Shown for the instant between the service starting and the first reconciled state. */
    fun connecting(context: Context): Notification {
        val builder = liveBuilder(context)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_connecting))
            .setLargeIcon(tile(context, ACCENT))
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
        return if (state.totalRunning == 1) agentCard(context, running.first()) else roster(context, state)
    }

    /** One conversation's card: the whole notification when it is the only agent running. */
    internal fun agentCard(context: Context, run: TrackedRun): Notification {
        val builder = liveBuilder(context)
            .setContentTitle(run.title)
            .setContentText(stepText(context, run))
            .setShortCriticalText(verb(context, run))
            .setLargeIcon(tile(context, tileHue(run.agentId)))
            .setWhen(run.startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openAgent(context, run.agentId))
        if (run.phase != LivePhase.Stopping) {
            builder.addAction(0, context.getString(R.string.notif_action_stop), stopRun(context, run))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // ProgressStyle keeps the step line in the collapsed row, so the header needs no verb of its own.
            builder.setStyle(journeyStyle(context, run))
        } else {
            // The legacy collapsed row drops the text line for the bar; the verb rides in the header instead.
            builder.setSubText(verb(context, run))
            builder.setProgress(JOURNEY_MAX, journeyProgress(run), false)
        }
        return builder.build()
    }

    /** The roster card: `11 agents running`, the fleet mosaic, and one row per tracked agent. */
    internal fun roster(context: Context, state: LiveActivityState): Notification {
        val ordered = sortedForRoster(state.running)
        val hues = tileHues(ordered)
        val now = AppClock.now()
        val rows = ordered.map { rosterRow(context, it, hues.getValue(it.agentId), now) }
        val tail = moreRow(context, state.untrackedCount)
        val count = state.totalRunning
        val title = context.resources.getQuantityString(R.plurals.notif_agents_running, count, count)
        return liveBuilder(context)
            .setContentTitle(title)
            .setContentText(rows.first())
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(join(rows + listOfNotNull(tail))))
            .setShortCriticalText(count.toString())
            .setNumber(count)
            .setLargeIcon(mosaic(context, ordered.map { hues.getValue(it.agentId) }, count))
            .setWhen(ordered.minOf { it.startedAtMillis })
            .setShowWhen(false)
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
     * One frost (or amber, while stopping) bar. [setStyledByProgress] mutes the unused tail; the working glyph walks
     * it; the PR icon is the destination. No setup / wrap-up segments and no milestone dots — those read as a wizard.
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

    // ---- roster rows --------------------------------------------------------------------------------------

    /**
     * `▎ Codex-Poly-Bot Scaling  Running · 34m`: the name in bold, then the verb and age muted, like an inbox row.
     * Android 16 strips every colour span from a promoted notification (only bold / italic / underline survive), so
     * there the row is just that; below 16 it opens with a thin edge in the agent's [hue] (amber while stopping).
     * A white edge on 16 would be noise, so none is drawn where its colour cannot show.
     */
    internal fun rosterRow(context: Context, run: TrackedRun, hue: Int, nowMillis: Long, colourSurvives: Boolean = Build.VERSION.SDK_INT < 36): CharSequence {
        val row = SpannableStringBuilder()
        if (colourSurvives) {
            row.append(ROW_EDGE)
            row.setSpan(ForegroundColorSpan(if (run.phase == LivePhase.Stopping) ORANGE else hue), 0, row.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val nameAt = row.length
        row.append(ellipsize(run.title, ROW_NAME_MAX))
        row.setSpan(StyleSpan(Typeface.BOLD), nameAt, row.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val detailAt = row.length
        // With colour the muted detail separates itself from the name; without it a dot has to.
        row.append(if (colourSurvives) ROW_GAP else ROW_SEP).append(rosterVerb(context, run))
        row.append(ROW_SEP).append(TimeFormat.relativeShort(run.startedAtMillis, nowMillis))
        row.setSpan(ForegroundColorSpan(MUTED), detailAt, row.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return row
    }

    /** The verb alone, one word (`Delegating to` → `Delegating`): the row has no room for the subject. */
    private fun rosterVerb(context: Context, run: TrackedRun): String = verb(context, run).substringBefore(' ')

    /** Muted `+N more` tail for running agents beyond the tracking cap; null when every running agent has a row. */
    internal fun moreRow(context: Context, untracked: Int): CharSequence? {
        if (untracked <= 0) return null
        val text = context.resources.getQuantityString(R.plurals.notif_more_agents, untracked, untracked)
        return SpannableStringBuilder(text).apply { setSpan(ForegroundColorSpan(MUTED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    // ---- pieces -------------------------------------------------------------------------------------------

    private fun liveBuilder(context: Context) = NotificationCompat.Builder(context, LiveNotifications.CHANNEL_LIVE)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setColor(ACCENT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setRequestPromotedOngoing(true)

    /** Current step, e.g. `Editing Composer.kt`. Bare verbs keep the ellipsis so they still read as in-flight. */
    internal fun stepText(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting) + "\u2026"
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping) + "\u2026"
        else -> run.digest.activity.let { if (it.detail.isNullOrBlank()) it.verb + "\u2026" else it.label }
    }

    /** The verb alone: header sub-text on the card below Android 16 and the status-bar chip on 16. */
    internal fun verb(context: Context, run: TrackedRun): String = when (run.phase) {
        LivePhase.Starting -> context.getString(R.string.notif_status_starting)
        LivePhase.Stopping -> context.getString(R.string.notif_status_stopping)
        else -> run.digest.activity.verb
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

    /** The hue an agent wears on its own; stable per id, so it keeps its colour across updates. */
    internal fun tileHue(agentId: String): Int = TILE_HUES[Math.floorMod(agentId.hashCode(), TILE_HUES.size)]

    /**
     * Hues for a whole roster: each agent's own [tileHue] unless an earlier agent already wears it, in which case the
     * next free hue — so up to six running conversations never share a colour, and an agent only changes colour when
     * a newcomer above it in the roster took its hue first.
     */
    internal fun tileHues(roster: List<TrackedRun>): Map<String, Int> {
        val taken = mutableSetOf<Int>()
        return roster.associate { run ->
            val preferred = Math.floorMod(run.agentId.hashCode(), TILE_HUES.size)
            val slot = (0 until TILE_HUES.size).map { (preferred + it) % TILE_HUES.size }.firstOrNull { it !in taken } ?: preferred
            taken += slot
            run.agentId to TILE_HUES[slot]
        }
    }

    /**
     * The fleet as one image for the roster's large-icon slot: a rounded dark square holding a grid of small tiles,
     * one per running agent in its hue (the palette cycles past the tracked eight), the remaining cells of the grid
     * faint — so the mosaic shows how many as well as which. 2×2 up to four agents, 3×3 up to nine, 4×4 beyond.
     */
    internal fun mosaic(context: Context, hues: List<Int>, total: Int): Bitmap {
        val size = (TILE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size * 0.22f
        paint.color = MOSAIC_BG
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
        val grid = mosaicGrid(total)
        val cells = (0 until grid * grid).map { index -> if (index < total) hues[index % hues.size.coerceAtLeast(1)] else MOSAIC_EMPTY }
        val inset = size * 0.16f
        val gap = size * 0.05f
        val cell = (size - 2 * inset - (grid - 1) * gap) / grid
        val cellRadius = cell * 0.28f
        cells.forEachIndexed { index, colour ->
            val col = index % grid
            val row = index / grid
            val left = inset + col * (cell + gap)
            val top = inset + row * (cell + gap)
            paint.color = colour
            canvas.drawRoundRect(left, top, left + cell, top + cell, cellRadius, cellRadius, paint)
        }
        return bitmap
    }

    internal fun mosaicGrid(total: Int): Int = when {
        total <= 4 -> 2
        total <= 9 -> 3
        else -> 4
    }

    /** Rounded square in [hue] with the official cube inked on it — the album-art slot on the single-agent card. */
    internal fun tile(context: Context, hue: Int): Bitmap {
        val size = (TILE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hue }
        val radius = size * 0.22f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_stat_agent)?.mutate()
        if (glyph != null) {
            glyph.setTint(TILE_INK)
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
