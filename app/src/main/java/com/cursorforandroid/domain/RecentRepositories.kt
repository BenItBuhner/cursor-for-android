package com.cursorforandroid.domain

/**
 * The repositories the composer pins at the top of the picker. A repository qualifies only while an agent has been
 * active in it within [RECENCY_WINDOW_MS]; the list is then the most recent of those, never more than [MAX_RECENT].
 * Two repositories worked on for weeks therefore stay two, not a padded ten of stale neighbours.
 */
object RecentRepositories {

    const val MAX_RECENT = 10
    const val RECENCY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    data class Partition(
        val recent: List<Repository>,
        val rest: List<Repository>,
    )

    /**
     * [repos] split into those with activity inside the window — newest first, at most [MAX_RECENT] — and the
     * remainder in catalogue order. Repositories are matched on their `owner/name` slug, the way [KnownBranches]
     * matches, so `https://github.com/Acme/App.git` and `github.com/acme/app` are the same repository.
     */
    fun partition(repos: List<Repository>, agents: List<Agent>, nowMillis: Long): Partition {
        val lastUsed = lastUsedBySlug(agents)
        val recent = repos
            .distinctBy { slug(it.url) ?: it.url }
            .mapNotNull { repo ->
                val key = slug(repo.url) ?: return@mapNotNull null
                val used = lastUsed[key] ?: return@mapNotNull null
                if (nowMillis - used >= RECENCY_WINDOW_MS) return@mapNotNull null
                repo to used
            }
            .sortedWith(compareByDescending<Pair<Repository, Long>> { it.second }.thenBy { it.first.slug.lowercase() })
            .take(MAX_RECENT)
            .map { it.first }
        val recentSlugs = recent.mapNotNull { slug(it.url) }.toSet()
        val rest = repos.filter { repo -> slug(repo.url)?.let { it !in recentSlugs } ?: true }
        return Partition(recent, rest)
    }

    /** Latest [Agent.updatedAtMillis] per repository slug; agents without a repository contribute nothing. */
    fun lastUsedBySlug(agents: List<Agent>): Map<String, Long> {
        val times = HashMap<String, Long>()
        for (agent in agents) {
            val key = agent.repoUrl?.let(::slug) ?: continue
            val previous = times[key]
            if (previous == null || agent.updatedAtMillis > previous) times[key] = agent.updatedAtMillis
        }
        return times
    }

    /** GitHub slugs are case-insensitive; a blank or unparseable URL matches nothing. */
    private fun slug(url: String): String? = url.takeIf { it.isNotBlank() }?.let(Agent::repoSlugOf)?.lowercase()
}
