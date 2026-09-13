package com.cursorforandroid.domain

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
        appendLine("rows (id · scope · signal · parent · kind · source · lifecycle · run · flags):")
        val order = compareBy<Agent>({ it.scope.ordinal }, { it.parent?.id ?: "" }, { it.id })
        rows.sortedWith(order).forEach { row ->
            val flags = listOfNotNull(
                "archived".takeIf { row.isArchived },
                "running".takeIf { row.isRunning },
                "pinned".takeIf { row.id in input.pinnedIds },
                "project".takeIf { row.isProject },
                "needsInput".takeIf { row.hasPendingInteraction },
                "knownScope=${row.knownScope?.name}".takeIf { row.knownScope != null && row.knownScope != row.scope },
            )
            appendLine(
                listOf(
                    "  ${tail(row.id)}",
                    row.scope.name.removePrefix("PROJECT_"),
                    row.scopeSignal?.name ?: signalOf(row, input),
                    row.parent?.let { tail(it.id) } ?: "-",
                    row.parent?.kind?.name ?: "-",
                    row.source?.name ?: "-",
                    row.lifecycle.name,
                    row.runStatus?.name ?: "-",
                    flags.joinToString(",").ifEmpty { "-" },
                ).joinToString(" "),
            )
        }
    }

    /** The registry's signal for a row nothing on the row itself names: what placed it, or "none" for the row's own facts. */
    private fun signalOf(row: Agent, input: Input): String {
        input.placementOf(row.id)?.let { return it.second.name }
        return when {
            row.parent != null -> "row.parent"
            row.source != null && row.source in AgentScope.CHILD_SOURCES -> "row.source"
            row.isProject -> "row.isProject"
            row.knownScope != null -> "kept"
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
