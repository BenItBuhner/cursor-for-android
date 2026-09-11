package com.cursorforandroid.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WidgetList
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything a widget render reads, in one value: a change to any of it is one re-render, and nothing else (a
 * refresh spinner, a transient error) is.
 */
data class WidgetSnapshot(
    val session: SessionState,
    /** True once the list has something to show — restored from disk or fetched. */
    val hasLoaded: Boolean,
    val agents: List<Agent>,
    val prefs: ListPreferences,
    val local: LocalAgentState,
    val theme: ThemeMode,
    val oledBlack: Boolean = false,
) {
    val isSignedOut: Boolean get() = session is SessionState.SignedOut

    fun rows(mode: WidgetMode, nowMillis: Long = AppClock.now()): List<AgentRow> = WidgetList.rows(mode, agents, prefs, local, nowMillis)
}

/**
 * What a widget render is allowed to spend on the network. A platform widget tick arrives every half hour whether or
 * not the app is being used, and it starts the process to do it, so it must not be the thing that reaches for a
 * connection that is not there or spends the last of the battery.
 */
data class WidgetRefreshBudget(val connected: Boolean, val batteryLow: Boolean) {
    val allowsRefresh: Boolean get() = connected && !batteryLow
}

/** The widget's view of the app graph: what to render, and how to get the list ready in a process that just started. */
object WidgetData {

    /** How old the list may be before a widget render revalidates it; the app itself refreshes after 30 s in the foreground. */
    private const val STALE_AFTER_MS = 5 * 60_000L

    /** How long a first render waits for a first page when nothing is on disk (a fresh install, or the demo). */
    private const val FIRST_PAGE_WAIT_MS = 4_000L

    /** Refreshes are joined here so they outlive the render that asked for them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun snapshots(graph: AppGraph): Flow<WidgetSnapshot> = combine(
        graph.session.state,
        graph.agents.state,
        graph.prefs.listPreferences,
        // The Git filter goes by the pull request states the app last read; the widget itself never asks GitHub.
        combine(graph.prefs.localAgentState, graph.pullRequests.states) { local, states -> local.copy(pullRequests = states) },
        combine(graph.prefs.themeMode, graph.prefs.oledBlack, ::Pair),
    ) { session, list, prefs, local, appearance ->
        WidgetSnapshot(session, list.hasLoaded, list.agents, prefs, local, appearance.first, appearance.second)
    }.distinctUntilChanged()

    suspend fun snapshot(graph: AppGraph): WidgetSnapshot = snapshots(graph).first()

    /**
     * Gets the list ready for a render. A widget update is often the first thing to run in a fresh process, so this
     * does what the app's start does: decides the session (stored key, demo, or signed out), shows the list saved on
     * disk, and revalidates it in the background. With nothing on disk it waits a moment for the first page instead,
     * so the widget's first frame has rows rather than "Loading…".
     *
     * Only ever the first page ([RefreshDepth.Quick]): the deeper passes exist so the app can tell a row that is gone
     * from a row beyond where the last pass stopped, which is worth several requests when a person is looking at the
     * list and nothing at all on a timer. Revalidation also waits for a connection and for the battery to recover.
     */
    suspend fun prepare(graph: AppGraph, budget: WidgetRefreshBudget) {
        graph.session.restoreIfNeeded()
        if (graph.session.state.value !is SessionState.SignedIn) return
        graph.agents.restoreFromCache()
        graph.pullRequests.restoreFromCache()
        if (graph.agents.state.value.hasLoaded) {
            if (budget.allowsRefresh) scope.launch { graph.agents.refreshIfStale(STALE_AFTER_MS, RefreshDepth.Quick) }
        } else if (budget.connected) {
            // Nothing to show yet, so this one is worth a low battery: the alternative is a widget that says
            // "Loading…" until the battery is charged.
            withTimeoutOrNull(FIRST_PAGE_WAIT_MS) { graph.agents.refresh(silent = true, depth = RefreshDepth.Quick) }
        }
    }

    /**
     * Rows for the launcher's widget picker: the demo's showcase chats in the states the sidebar can show — working
     * (on a self-hosted machine), unread, read with a branch, failed — so the preview is the widget, not a mock-up.
     */
    fun sample(theme: ThemeMode, nowMillis: Long = AppClock.now()): WidgetSnapshot {
        val minute = 60_000L
        fun agent(
            id: String,
            name: String,
            ageMinutes: Long,
            repo: String,
            status: RunStatus = RunStatus.FINISHED,
            lifecycle: AgentLifecycle = AgentLifecycle.IDLE,
            branch: String? = null,
            pr: String? = null,
            env: EnvType = EnvType.CLOUD,
        ) = Agent(
            id = id,
            name = name,
            lifecycle = lifecycle,
            runStatus = status,
            envType = env,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = nowMillis - (ageMinutes + 30) * minute,
            updatedAtMillis = nowMillis - ageMinutes * minute,
            latestRunId = "run-$id",
            repoUrl = "https://github.com/bennett/$repo",
            startingRef = "main",
            branches = if (branch != null) listOf(GitBranch("https://github.com/bennett/$repo", branch, pr)) else emptyList(),
        )
        val agents = listOf(
            agent("bc-preview-1", "Cli exploration", 19, "cursor-for-android", branch = "cursor/cli-exploration-9c1d"),
            agent("bc-preview-2", "House environment overhaul", 30, "visual-engine", branch = "cursor/house-environment-7b3e", pr = "https://github.com/bennett/visual-engine/pull/67"),
            agent("bc-preview-3", "Codex-Poly-Bot Scaling", 34, "codex-poly-bot", status = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE, env = EnvType.MACHINE),
            agent("bc-preview-4", "Market replay engine", 52, "market-replay", status = RunStatus.ERROR),
            agent("bc-preview-5", "Revenue Scaling Pipeline Research", 120, "cesium", branch = "cursor/revenue-pipeline-3f2a", pr = "https://github.com/techlitnow/cesium/pull/214"),
            agent("bc-preview-6", "Latest release process", 180, "cursor-for-android", branch = "cursor/release-process-1a2b"),
            agent("bc-preview-7", "Realistic city apartment scene", 240, "visual-engine", branch = "cursor/city-apartment-5d6e"),
        )
        return WidgetSnapshot(
            session = SessionState.Loading,
            hasLoaded = true,
            agents = agents,
            prefs = ListPreferences(),
            // Two read rows that pushed — one with an open PR (the pull-request glyph in green), one with a branch
            // alone (the neutral branch glyph); the rest are unread.
            local = LocalAgentState(
                readMarkers = mapOf("bc-preview-5" to nowMillis, "bc-preview-6" to nowMillis),
                pullRequests = mapOf("https://github.com/techlitnow/cesium/pull/214" to PullRequestState.Open),
            ),
            theme = theme,
        )
    }
}

/** What the device says right now, for the render to pass to [WidgetData.prepare]. */
internal fun deviceRefreshBudget(context: Context): WidgetRefreshBudget =
    WidgetRefreshBudget(connected = hasInternet(context), batteryLow = isBatteryLow(context))

private fun hasInternet(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
    val active = manager.activeNetwork ?: return@runCatching false
    manager.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)

/** The same threshold `JobInfo.setRequiresBatteryNotLow` uses, read from the sticky battery broadcast. */
private fun isBatteryLow(context: Context): Boolean = runCatching {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching false
    if (status.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)) return@runCatching true
    // Not low while charging, however little is in it: what goes back in outweighs a page of a list.
    if (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return@runCatching false
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    level >= 0 && scale > 0 && level * 100 / scale <= LOW_BATTERY_PERCENT
}.getOrDefault(false)

private const val LOW_BATTERY_PERCENT = 15
