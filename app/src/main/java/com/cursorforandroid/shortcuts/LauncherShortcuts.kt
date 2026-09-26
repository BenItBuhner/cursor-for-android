package com.cursorforandroid.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.cursorforandroid.AppGraph
import com.cursorforandroid.R
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.LauncherShortcutPicks
import com.cursorforandroid.domain.LauncherShortcutPicks.Pick
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.ui.theme.ProjectPalette
import com.cursorforandroid.widget.ShortcutIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toArgb

/**
 * The launcher's long-press menu on the app icon. Two entries are fixed and declared in the manifest — New chat,
 * which opens the quick composer over the launcher, and Search, which opens the app on the sidebar's search — and
 * the rest are the sidebar's pinned chats and Projects ([LauncherShortcutPicks]), published as dynamic shortcuts
 * and kept in step with the list, the pins and the Projects for as long as the process lives (the launcher keeps
 * what was last published). Signing out takes them down: the menu must not name another account's chats.
 */
object LauncherShortcuts {

    /** A burst of changes (a refresh's pages, a pin toggled twice) settles into one publication. */
    private const val SETTLE_MS = 1_500L

    /** Beyond the two fixed entries, this many: the launchers Android ships show four or five in all. */
    private const val MAX_DYNAMIC = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** From the application's deferred start. Idempotent. */
    fun start(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch {
                picks(graph, slots(app)).distinctUntilChanged().collectLatest { picks ->
                    delay(SETTLE_MS)
                    // A publication under way is finished even if a newer change arrives; the next follows it.
                    withContext(NonCancellable) { publish(app, picks) }
                }
            }
        }
    }

    /**
     * What the menu should hold as the app's state stands: the picks while signed in, null while signed out or
     * before the session is decided (nothing is published for a session that is not yet known).
     */
    internal fun picks(graph: AppGraph, max: Int): Flow<List<Pick>?> = combine(
        graph.session.state,
        graph.agents.state,
        graph.prefs.listPreferences,
        graph.prefs.localAgentState,
        graph.agents.knownRoots,
    ) { session, list, prefs, local, roots ->
        when (session) {
            is SessionState.SignedIn -> LauncherShortcutPicks.pick(AgentListOrganizer.organize(list.shownAgents, prefs, local, knownRoots = roots), max)
            SessionState.SignedOut -> emptyList()
            SessionState.Loading -> null
        }
    }

    /** How many dynamic entries to publish: the launcher's own cap less the two manifest entries, at most [MAX_DYNAMIC]. */
    private fun slots(context: Context): Int =
        runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(4).let { (it - STATIC_COUNT).coerceIn(0, MAX_DYNAMIC) }

    private fun publish(context: Context, picks: List<Pick>?) {
        if (picks == null) return
        runCatching {
            if (picks.isEmpty()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, picks.mapIndexed { rank, pick -> shortcut(context, pick, rank) })
        }
    }

    /** One menu entry: the chat's name, its glyph on a disc in the Project's own tone, and the deep link the app opens it by. */
    private fun shortcut(context: Context, pick: Pick, rank: Int): ShortcutInfoCompat {
        val target = pick.target
        val agent = pick.row.agent
        val label = agent.name.ifBlank { if (target is ShortcutTarget.Project) "Project" else "Chat" }
        val icon = when (target) {
            is ShortcutTarget.Project -> ShortcutIcons.disc(context, R.drawable.widget_cube, tone = ProjectPalette.color(agent.projectAppearance?.colorId, dark = true)?.toArgb() ?: DEFAULT_DISC)
            else -> ShortcutIcons.disc(context, R.drawable.widget_sparkle, tone = DEFAULT_DISC)
        }
        return ShortcutInfoCompat.Builder(context, "open:${agent.id}")
            .setShortLabel(label.take(SHORT_LABEL_MAX))
            .setLongLabel(label.take(LONG_LABEL_MAX))
            .setIcon(icon)
            .setIntent(ShortcutIntents.forTarget(context, target))
            .setRank(rank)
            .build()
    }

    /** The two manifest entries (res/xml/shortcuts.xml). */
    private const val STATIC_COUNT = 2
    /** The launcher's own limits (`ShortcutManager`): 10 and 25 characters. */
    private const val SHORT_LABEL_MAX = 10
    private const val LONG_LABEL_MAX = 25
    /** The app icon's own background (`ic_launcher_background`), for a chat that is no Project's. */
    private val DEFAULT_DISC = Color.parseColor("#14120B")
}

/**
 * Shortcut icons drawn as adaptive bitmaps: a disc of [tone] filling the whole canvas — the launcher masks it to
 * its own shape, as it does the app icon — with the glyph in white at the size the safe zone allows. Drawn rather
 * than declared, so a Project's shortcut can carry the Project's colour, which no resource knows.
 */
internal object ShortcutIcons {

    /** The adaptive icon canvas (108dp) and the glyph within its 66dp safe zone. */
    private const val CANVAS_DP = 108f
    private const val GLYPH_DP = 44f

    fun disc(context: Context, @DrawableRes glyph: Int, tone: Int): IconCompat {
        val metrics = context.resources.displayMetrics
        val side = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CANVAS_DP, metrics).toInt().coerceAtLeast(1)
        val glyphPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, GLYPH_DP, metrics).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tone)
        val drawable = context.getDrawable(glyph)
        if (drawable != null) {
            drawable.setTint(Color.WHITE)
            val glyphBitmap = drawable.toBitmap(glyphPx, glyphPx)
            canvas.drawBitmap(glyphBitmap, (side - glyphPx) / 2f, (side - glyphPx) / 2f, null)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
