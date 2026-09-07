package com.cursorforandroid.domain

/**
 * A branch the composer can offer as a starting point, and how the app knows about it: [pushedBy] names the agent
 * that pushed it (the most recent one, when several did), [startedAgents] counts the agents launched from it.
 */
data class BranchOption(
    val name: String,
    val pushedBy: String?,
    val startedAgents: Int,
    val lastUsedAtMillis: Long,
) {
    /** One line saying why the branch is in the list. */
    val description: String
        get() = when {
            pushedBy != null -> "Pushed by $pushedBy"
            startedAgents == 1 -> "Starting point of 1 agent"
            else -> "Starting point of $startedAgents agents"
        }
}

/**
 * The branches known for a repository. The Cloud Agents API has no branch listing (`GET /v1/repositories` returns
 * bare URLs), so the picker is built from the agent list, the way the repository picker is seeded from it: every
 * ref an agent of the repository was launched from, and every branch one pushed. Repositories are matched on their
 * `owner/name` slug, since runs report `github.com/owner/repo` where the agent record has the full URL.
 */
object KnownBranches {

    private class Seen {
        var pushedBy: String? = null
        var pushedAt = 0L
        var started = 0
        var lastUsed = 0L
    }

    /** Branches seen on agents of [repoUrl], most recently active first; starting points before pushed branches on a tie. */
    fun forRepository(agents: List<Agent>, repoUrl: String): List<BranchOption> {
        val wanted = slug(repoUrl) ?: return emptyList()
        val seen = LinkedHashMap<String, Seen>()
        for (agent in agents) {
            val agentSlug = agent.repoUrl?.let(::slug)
            if (agentSlug == wanted) {
                agent.startingRef?.trim()?.takeIf { it.isNotEmpty() }?.let { ref ->
                    val entry = seen.getOrPut(ref) { Seen() }
                    entry.started++
                    entry.lastUsed = maxOf(entry.lastUsed, agent.updatedAtMillis)
                }
            }
            for (pushed in agent.branches) {
                val name = pushed.branch?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                // The legacy list only knows the agent's repository, so a blank branch URL means that one.
                val branchSlug = slug(pushed.repoUrl) ?: agentSlug
                if (branchSlug != wanted) continue
                val entry = seen.getOrPut(name) { Seen() }
                if (entry.pushedBy == null || agent.updatedAtMillis > entry.pushedAt) {
                    entry.pushedBy = agent.name
                    entry.pushedAt = agent.updatedAtMillis
                }
                entry.lastUsed = maxOf(entry.lastUsed, agent.updatedAtMillis)
            }
        }
        return seen
            .map { (name, entry) -> BranchOption(name, entry.pushedBy, entry.started, entry.lastUsed) }
            .sortedWith(compareByDescending<BranchOption> { it.lastUsedAtMillis }.thenBy { it.pushedBy != null }.thenBy { it.name })
    }

    /** GitHub slugs are case-insensitive; a blank or unparseable URL matches nothing. */
    private fun slug(url: String): String? = url.takeIf { it.isNotBlank() }?.let(Agent::repoSlugOf)?.lowercase()

    /**
     * Whether [name] could name a branch (or a commit) — git's `check-ref-format --branch` rules. The picker offers
     * to use what was typed only once this holds, so a half-typed prefix like `cursor/` is not offered as a branch.
     */
    fun isPlausibleRef(name: String): Boolean {
        if (name.isEmpty() || name == "@") return false
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) return false
        if ("//" in name || ".." in name || "@{" in name) return false
        if (name.any { it <= ' ' || it == '\u007f' || it in "~^:?*[\\" }) return false
        return name.split('/').none { it.startsWith(".") || it.endsWith(".lock") }
    }
}
