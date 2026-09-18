package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import java.time.Instant
import java.time.ZoneId

/**
 * The redacted account of where every chat was placed, for a Project's chat that still shows among the account's
 * own. One line per row: the id's tail, the scope decided, which signal decided it (see [LineageSignal]), the parent's
 * tail and the kind of membership, the source, lifecycle and run state. No names, no prompts, no tokens, no
 * repository or branch: nothing in it is the user's text, so it can be sent as it is. The header says which mode the
 * app was in and what the account's reads answered per Project, which is where a leak is usually explained.
 */
object ProjectDiagnostics {

    /** What one root's last membership pass answered, as the report prints it. */
    data class RootSync(val workersRead: Boolean, val childrenRead: Boolean, val workerCount: Int, val childCount: Int, val notice: String?)

    data class Input(
        val appVersion: String,
        val nowIso: String,
        val extendedMode: Boolean,
        val projectsCapability: Boolean,
        val accountSession: Boolean,
        val listFromCache: Boolean,
        val lastRefreshedIso: String?,
        val agents: List<Agent>,
        /** The registry's word on a chat, when it has one: the parent (null for a root) and the signal. */
        val placementOf: (String) -> Pair<AgentParent?, LineageSignal>?,
        val rootSyncs: Map<String, RootSync>,
        val pinnedIds: Set<String> = emptySet(),
        /** Pinned ids the list does not hold and could not fetch (see `AgentRepository.unresolvedPinned`). */
        val unresolvedPinned: Set<String> = emptySet(),
        /** The dedicated running pass, when one has run (see [RunningScan]). */
        val runningScan: RunningScan? = null,
        /** Rows asked for an account record that gave none or could not be asked (see `AgentRepository.unresolvedRecords`). */
        val unresolvedRecords: Set<String> = emptySet(),
        val envTypes: Map<String, String> = emptyMap(),
        /** The root registry (see [KnownRoot]): every Project known, loaded or not. */
        val knownRoots: List<KnownRoot> = emptyList(),
        /** Registry roots the list does not hold and could not fetch. */
        val unresolvedRoots: Set<String> = emptySet(),
        /** The last root discovery pass: roots found, pages read, whether it reached the end, when; null before one. */
        val rootScan: RootScanSummary? = null,
        val memberCounts: Map<String, Int> = emptyMap(),
        /** The account round (the pin repository's): whether it was ever started this process, and how its last round went. */
        val accountRound: AccountRoundSummary? = null,
        /** What the last fetch by id of each unresolved root answered. */
        val rootFailures: Map<String, String> = emptyMap(),
        /** Settings › Notifications, the Project half: what the live count includes, whose finishes are cards. */
        val notificationPrefs: ProjectNotificationPrefs = ProjectNotificationPrefs.DEFAULT,
        /** Chats workers' records name as manager (information: the desktop makes no Project of a manager). */
        val managerCandidates: Set<String> = emptySet(),
        /** The moment the report is for, in millis: what each row's time bucket is read against. */
        val nowMillis: Long = AppClock.now(),
        /** What the last refresh cost, stage by stage (see [RefreshStats]); null before a refresh this process. */
        val refresh: RefreshStats.Snapshot? = null,
    )

    data class RootScanSummary(
        val status: String,
        val rootsFound: Int,
        val pagesRead: Int,
        val records: Int,
        val complete: Boolean,
        val atIso: String?,
        val notice: String?,
        val attempts: Int,
    )

    data class AccountRoundSummary(
        /** The pin repository was built this process: the account's list has been asked for at least once. */
        val started: Boolean,
        val active: Boolean,
        val syncing: Boolean,
        val lastSyncedIso: String?,
        val error: String?,
    )

    fun render(input: Input): String = buildString {
        appendLine("Cursor for Android ${input.appVersion} · Project diagnostics · ${input.nowIso}")
        appendLine("mode=${if (input.extendedMode) "extended" else "default"} projects=${input.projectsCapability} accountSession=${input.accountSession} listFromCache=${input.listFromCache} lastRefreshed=${input.lastRefreshedIso ?: "-"}")
        val rows = input.agents
        val roots = rows.filter { it.isProjectRoot }
        val children = rows.filter { it.isProjectChild }
        val primaries = rows.filter { !it.isProjectScoped }
        appendLine("rows=${rows.size} primary=${primaries.size} roots=${roots.size} children=${children.size} running=${rows.count { it.isRunning }} archived=${rows.count { it.isArchived }}")
        val orphans = children.filter { child -> child.parent != null && rows.none { it.id == child.parent.id } }
        if (orphans.isNotEmpty()) appendLine("childrenWithoutLoadedParent=${orphans.size} parents=${orphans.mapNotNull { it.parent?.id }.distinct().joinToString(",") { tail(it) }}")

        appendLine()
        appendLine("pinned (id · resolution · scope · env):")
        if (input.pinnedIds.isEmpty()) appendLine("  none")
        input.pinnedIds.sorted().forEach { id ->
            val row = rows.firstOrNull { it.id == id }
            val resolution = when {
                row != null -> "shown"
                id in input.unresolvedPinned -> "UNRESOLVED (not in the pages, fetch by id failed)"
                else -> "MISSING (not in the pages, fetch pending)"
            }
            appendLine("  ${tail(id)} $resolution ${row?.scope?.name?.removePrefix("PROJECT_") ?: "-"} ${row?.envType?.name ?: "-"}")
        }

        appendLine()
        val scan = input.runningScan
        val listRunning = rows.filter { it.isRunning }
        val counted = listRunning.filter { input.notificationPrefs.countsLive(it) }
        appendLine("running: list=${listRunning.size} counted=${counted.size} excludedByEvidence=${listRunning.size - counted.size}" +
            (scan?.let { " scan=${it.ids.size} pages=${it.pagesRead} complete=${it.complete} scannedAt=${if (it.scannedAtMillis > 0) java.time.Instant.ofEpochMilli(it.scannedAtMillis) else "-"} account=${it.accountIds?.size ?: "-"} accountAt=${if (it.accountAtMillis > 0) java.time.Instant.ofEpochMilli(it.accountAtMillis) else "-"}" } ?: " scan=never"))
        if (scan != null) {
            appendLine("running set (id · in scan · in account · in list · scope · counted):")
            (scan.all + listRunning.map { it.id }).sorted().forEach { id ->
                val row = rows.firstOrNull { it.id == id }
                appendLine(
                    "  ${tail(id)} ${if (id in scan.ids) "scan" else "-"} ${if (scan.accountIds?.contains(id) == true) "account" else "-"} " +
                        "${if (row == null) "NOT-LOADED" else if (row.isRunning) "running" else "row:${row.runStatus?.name ?: "-"}"} ${row?.scope?.name?.removePrefix("PROJECT_") ?: "-"} " +
                        (if (row != null && row.isRunning) (if (input.notificationPrefs.countsLive(row)) "counted" else "excluded") else "-"),
                )
            }
        }
        val bare = rows.filter { it.record == null }
        appendLine("records: read=${rows.size - bare.size} notRead=${bare.size} unresolved=${input.unresolvedRecords.size}" + (if (bare.isNotEmpty()) " notRead=${bare.map { it.id }.sorted().take(40).joinToString(",") { tail(it) }}" else ""))

        appendLine()
        val round = input.accountRound
        appendLine(
            "account round: " + when {
                round == null -> "not started this process (the pin repository was never built)"
                else -> "started active=${round.active} syncing=${round.syncing} lastSynced=${round.lastSyncedIso ?: "never"}" + (round.error?.let { " error=\"${redactNotice(it)}\"" } ?: "")
            },
        )
        val rootScan = input.rootScan
        appendLine("root registry: known=${input.knownRoots.size} withRow=${input.knownRoots.count { r -> rows.any { it.id == r.id } }} standIns=${input.knownRoots.count { r -> rows.none { it.id == r.id } }} unresolved=${input.unresolvedRoots.size}")
        appendLine(
            "root scan: " + (rootScan?.let {
                "${it.status.lowercase()} roots:${it.rootsFound} pages:${it.pagesRead} records:${it.records} complete:${it.complete} attempts:${it.attempts} at:${it.atIso ?: "-"}" +
                    (it.notice?.let { n -> " notice=\"${redactNotice(n)}\"" } ?: "")
            } ?: "never"),
        )
        input.rootFailures.entries.sortedBy { it.key }.forEach { (id, why) -> appendLine("  fetch by id failed ${tail(id)}: ${redactNotice(why)}") }
        appendLine("desktop rules (Cursor 3.20.21 workbench.glass.main.js): subagentParentId = cloudSubagentParent.parentAgentId || sideChatInfo.parentBcId || managerAgentId; PJr/mQa: a row with one is a child under that parent's row, never top level, whatever the parent is; kf: a top-level row with project_metadata is a Project; VuC: Pinned = pinned top-level non-Projects; Nlc: pinned rows pass every filter, archived rows only the Archived filter, Projects skip the git filter; f3v: Today / Yesterday / Last 7 Days / Last 30 Days / Older; rUm: lastUpdatedAt desc, id asc")
        appendLine("managers named by worker records (no Project of them unless their own record carries project_metadata): ${input.managerCandidates.filter { id -> input.knownRoots.none { it.id == id } }.sorted().joinToString(" ") { tail(it) }.ifEmpty { "-" }}")
        appendLine("registry (id · signal · evidence · row · archived · members · seen), each with the record's raw fields:")
        input.knownRoots.sortedBy { it.id }.forEach { root ->
            val row = rows.firstOrNull { it.id == root.id }
            appendLine(
                "  ${tail(root.id)} ${root.signal.name} [${root.evidence}] ${if (row != null) "row" else if (root.id in input.unresolvedRoots) "UNRESOLVED" else "stand-in"} " +
                    "${if (root.archived) "archived" else "-"} ${input.memberCounts[root.id] ?: "-"} ${if (root.lastSeenMillis > 0) java.time.Instant.ofEpochMilli(root.lastSeenMillis) else "-"}",
            )
            appendLine("    record: ${root.record?.describe(::tail) ?: "not read this session"}")
        }

        appendLine()
        appendLine("roots (id · signal · children by kind · last membership pass):")
        roots.sortedBy { it.id }.forEach { root ->
            val kids = children.filter { it.parent?.id == root.id }
            val byKind = AgentParentKind.entries.map { kind -> "${kind.name.lowercase()}=${kids.count { it.parent?.kind == kind }}" }.joinToString(" ")
            val sync = input.rootSyncs[root.id]?.let { s ->
                "sync=workers:${if (s.workersRead) "ok(${s.workerCount})" else "failed"} children:${if (s.childrenRead) "ok(${s.childCount})" else "failed"}" + (s.notice?.let { " notice=\"${redactNotice(it)}\"" } ?: "")
            } ?: "sync=never"
            appendLine("  ${tail(root.id)} signal=${root.scopeSignal?.name ?: signalOf(root, input)} $byKind $sync")
        }
        val pendingRoots = input.agents.asSequence().mapNotNull { it.parent?.id }.distinct().filter { id -> rows.none { it.id == id } }.toList()
        pendingRoots.forEach { appendLine("  ${tail(it)} (not loaded) ${input.rootSyncs[it]?.let { s -> "sync=workers:${s.workersRead} children:${s.childrenRead}" } ?: "sync=never"}") }

        appendLine()
        appendLine("refresh:")
        val refresh = input.refresh
        if (refresh == null) {
            appendLine("  none this process")
        } else {
            appendLine("  started=${Instant.ofEpochMilli(refresh.startedAtMillis)} spinnerReleased=${refresh.spinnerMs?.let { "+${it}ms" } ?: "-"} lastStageEnded=${refresh.settledMs?.let { "+${it}ms" } ?: "-"} calls=${refresh.totalCalls}")
            appendLine("  stages (start → end, calls):")
            refresh.stages.sortedBy { it.startedAtMillis }.forEach { stage ->
                val start = stage.startedAtMillis - refresh.startedAtMillis
                val end = stage.endedAtMillis - refresh.startedAtMillis
                appendLine("    +${start}ms → +${end}ms (${stage.durationMs}ms)  ${stage.calls} × ${stage.name}${stage.note?.let { " · $it" } ?: ""}")
            }
        }
        appendLine()
        appendLine("rows (id · scope · signal · parent · kind · source · env · lifecycle · run · flags), each with the desktop rule that placed it and the record's raw fields:")
        val loaded = rows.mapTo(HashSet()) { it.id }
        val archivedIds = rows.filter { it.isArchived }.mapTo(HashSet()) { it.id }
        val order = compareBy<Agent>({ it.scope.ordinal }, { it.parent?.id ?: "" }, { it.id })
        rows.sortedWith(order).forEach { row ->
            val flags = listOfNotNull(
                "archived".takeIf { row.isArchived },
                "running".takeIf { row.isRunning },
                "pinned".takeIf { row.id in input.pinnedIds },
                "project".takeIf { row.isProject },
                "needsInput".takeIf { row.hasPendingInteraction },
                "stamp=${input.placementOf(row.id)?.second?.name}".takeIf { input.placementOf(row.id) != null },
            )
            appendLine(
                listOf(
                    "  ${tail(row.id)}",
                    row.scope.name.removePrefix("PROJECT_"),
                    signalOf(row, input),
                    row.parent?.let { tail(it.id) } ?: "-",
                    row.parent?.kind?.name ?: "-",
                    row.source?.name ?: "-",
                    row.envType.name,
                    row.lifecycle.name,
                    row.runStatus?.name ?: "-",
                    flags.joinToString(",").ifEmpty { "-" },
                ).joinToString(" "),
            )
            appendLine("    rule: ${AgentsWindowList.place(row, loaded, input.pinnedIds, archivedIds).rule}")
            appendLine("    record: ${row.record?.describe(::tail) ?: "not read" + (if (row.id in input.unresolvedRecords) " (asked; the account gave none)" else if (input.accountSession) " (fetch by id pending)" else " (default mode: the public API alone)")}")
            appendLine("    time: ${timeOf(row, input)}")
        }
    }

    /**
     * What the row is dated by and where it lands: the listed time (the desktop's `lastUpdatedAt`) and its bucket,
     * the record's own activity (`lastMessageActivityAtMs ?? updatedAtMs`, the desktop's field) and the public
     * API's `updatedAt`, so a chat sitting under Today can be read back to the field that put it there.
     */
    private fun timeOf(row: Agent, input: Input): String {
        val now = input.nowMillis
        fun iso(millis: Long) = if (millis > 0) Instant.ofEpochMilli(millis).toString() else "-"
        val bucket = AgentsWindowList.timeBucket(row.listedAtMillis, now, ZoneId.systemDefault()).label
        val by = if (row.activityAtMillis != null) "record" else "publicApi"
        return "listed=${iso(row.listedAtMillis)} bucket=\"$bucket\" by=$by recordActivity=${row.activityAtMillis?.let(::iso) ?: "not read"} publicUpdated=${iso(row.updatedAtMillis)} created=${iso(row.createdAtMillis)}"
    }

    /** Which word gave the row its parent link or flag: the row's own signal, else the stamp on it, else the row's facts. */
    private fun signalOf(row: Agent, input: Input): String {
        row.scopeSignal?.let { signal -> return signal.name + (input.placementOf(row.id)?.let { (_, stamp) -> if (stamp != signal) "+stamp:${stamp.name}" else "" } ?: "") }
        input.placementOf(row.id)?.let { return "stamp:${it.second.name}" }
        return when {
            row.parent != null -> "row.parent"
            row.isProject -> "row.isProject"
            else -> "none"
        }
    }

    /** The last [TAIL] characters of an id: enough to match rows against each other, not enough to name the chat. */
    fun tail(id: String): String = if (id.length <= TAIL) id else "…" + id.takeLast(TAIL)

    /** A notice keeps its words but not any id, URL or quoted text an error message might carry. */
    private fun redactNotice(notice: String): String =
        notice.replace(Regex("""bc-[A-Za-z0-9-]+"""), "bc-…").replace(Regex("""https?://[^\s)"]+"""), "<url>").replace(Regex("\"[^\"]*\""), "\"…\"").take(160)

    private const val TAIL = 6
}
